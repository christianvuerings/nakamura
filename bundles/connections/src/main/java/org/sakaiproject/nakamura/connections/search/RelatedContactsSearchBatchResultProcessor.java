/*
 * Licensed to the Sakai Foundation (SF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The SF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.sakaiproject.nakamura.connections.search;

import static org.sakaiproject.nakamura.api.search.solr.SolrSearchConstants.DEFAULT_PAGED_ITEMS;
import static org.sakaiproject.nakamura.api.search.solr.SolrSearchConstants.PARAMS_ITEMS_PER_PAGE;

import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Properties;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.apache.sling.commons.json.JSONException;
import org.apache.sling.commons.json.io.JSONWriter;
import org.sakaiproject.nakamura.api.connections.ConnectionConstants;
import org.sakaiproject.nakamura.api.connections.ConnectionManager;
import org.sakaiproject.nakamura.api.connections.ConnectionState;
import org.sakaiproject.nakamura.api.lite.Session;
import org.sakaiproject.nakamura.api.lite.StorageClientException;
import org.sakaiproject.nakamura.api.lite.StorageClientUtils;
import org.sakaiproject.nakamura.api.lite.accesscontrol.AccessDeniedException;
import org.sakaiproject.nakamura.api.lite.authorizable.Authorizable;
import org.sakaiproject.nakamura.api.lite.authorizable.AuthorizableManager;
import org.sakaiproject.nakamura.api.lite.authorizable.Group;
import org.sakaiproject.nakamura.api.lite.authorizable.User;
import org.sakaiproject.nakamura.api.search.solr.Query;
import org.sakaiproject.nakamura.api.search.solr.Result;
import org.sakaiproject.nakamura.api.search.solr.SolrSearchBatchResultProcessor;
import org.sakaiproject.nakamura.api.search.solr.SolrSearchConstants;
import org.sakaiproject.nakamura.api.search.solr.SolrSearchException;
import org.sakaiproject.nakamura.api.search.solr.SolrSearchResultSet;
import org.sakaiproject.nakamura.api.search.solr.SolrSearchServiceFactory;
import org.sakaiproject.nakamura.api.search.solr.SolrSearchUtil;
import org.sakaiproject.nakamura.api.user.BasicUserInfo;
import org.sakaiproject.nakamura.util.ExtendedJSONWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * <pre>
 * KERN-1798
 * Create a feed that lists people related to My Contacts. The criteria that 
 * should be used for this are: 
 * 
 * - Contacts from my contacts 
 * - People with similar tags, directory locations or descriptions 
 * - People that have commented on content I have commented on 
 * - People that are a member of groups I'm a member of 
 * 
 * The feed should not include people that are already contacts of mine. 
 * 
 * When less than 11 items are found for these criteria, the feed should be 
 * filled up with random people. However, preference should be given to people 
 * that have a profile picture, and a high number of contacts, memberships and 
 * content items.
 * </pre>
 */
@Component(immediate = true, metatype = true)
@Properties(value = {
    @Property(name = "service.vendor", value = "The Sakai Foundation"),
    @Property(name = SolrSearchConstants.REG_BATCH_PROCESSOR_NAMES, value = "RelatedContactsSearchBatchResultProcessor") })
@Service(value = SolrSearchBatchResultProcessor.class)
public class RelatedContactsSearchBatchResultProcessor implements
    SolrSearchBatchResultProcessor {

  /**
   * "These go to eleven"
   */
  public static final int VOLUME = 11;

  private static final Logger LOG = LoggerFactory
      .getLogger(RelatedContactsSearchBatchResultProcessor.class);

  @Reference
  private SolrSearchServiceFactory searchServiceFactory;

  @Reference
  private ConnectionManager connectionManager;

  /**
   * {@inheritDoc}
   * 
   * @see org.sakaiproject.nakamura.api.search.solr.SolrSearchBatchResultProcessor#writeResults(org.apache.sling.api.SlingHttpServletRequest,
   *      org.apache.sling.commons.json.io.JSONWriter, java.util.Iterator)
   */
  public void writeResults(final SlingHttpServletRequest request,
      final JSONWriter writer, final Iterator<Result> iterator) throws JSONException {

    final Session session = StorageClientUtils.adaptToSession(request
        .getResourceResolver().adaptTo(javax.jcr.Session.class));
    final List<String> connectedUsers = connectionManager.getConnectedUsers(request,
        session.getUserId(), ConnectionState.ACCEPTED);
    final long nitems = SolrSearchUtil.longRequestParameter(request,
        PARAMS_ITEMS_PER_PAGE, DEFAULT_PAGED_ITEMS);
    // TODO add proper paging support
    // final long page = SolrSearchUtil.longRequestParameter(request, PARAMS_PAGE, 0);

    final Set<String> processedUsers = new HashSet<String>();
    try {
      while (iterator.hasNext() && processedUsers.size() < nitems) {
        final Result result = iterator.next();
        final String resourceType = (String) result.getFirstValue("resourceType");
        if (ConnectionConstants.SAKAI_CONTACT_RT.equals(resourceType)) {
          renderConnection(request, writer, result, connectedUsers, processedUsers);
        } else if ("authorizable".equals(resourceType)) {
          renderAuthorizable(request, writer, result, connectedUsers, processedUsers);
        } else {
          LOG.warn("TODO: add missing handler for this resource type: {}: {}",
              result.getPath(), result.getProperties());
        }
      }
      if (processedUsers.size() < nitems) {
        // Add people that are a member of groups I'm a member of
        final AuthorizableManager authMgr = session.getAuthorizableManager();
        final Authorizable auth = authMgr.findAuthorizable(session.getUserId());
        final Set<String> relatedUsers = new HashSet<String>();
        final String[] principals = auth.getPrincipals();
        if (principals != null) {
          // process the groups randomly because we might hit page size any time
          final List<String> randomPrincipals = Arrays.asList(principals);
          Collections.shuffle(randomPrincipals);
          for (int i = 0; i < randomPrincipals.size() && processedUsers.size() < nitems; i++) {
            final Group group = (Group) authMgr.findAuthorizable(randomPrincipals.get(i));
            if (group != null) {
              final String[] members = group.getMembers();
              if (members != null) {
                // TODO should add a gate to restrict number of adds to max page size
                relatedUsers.addAll(Arrays.asList(members));
              }
            }
          }
          // randomize the list because we want different people showing up each time
          final List<String> relatedPeopleFromGroupMembers = new ArrayList<String>(
              relatedUsers);
          Collections.shuffle(relatedPeopleFromGroupMembers);
          for (final String user : relatedPeopleFromGroupMembers) {
            renderContact(user, request, writer, connectedUsers, processedUsers);
          }
        }
      }
      if (processedUsers.size() < VOLUME) {
        LOG.info(
            "Did not meet functional specification. There should be at least {} results; actual size was: {}",
            VOLUME, processedUsers.size());
      }
    } catch (AccessDeniedException e) {
      // quietly swallow access denied
      LOG.debug(e.getLocalizedMessage(), e);
    } catch (StorageClientException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * @param request
   * @param writer
   * @param result
   * @param connectedUsers
   * @param processedUsers
   * @throws AccessDeniedException
   * @throws JSONException
   * @throws StorageClientException
   */
  protected void renderConnection(SlingHttpServletRequest request, JSONWriter writer,
      Result result, final List<String> connectedUsers, final Set<String> processedUsers)
      throws AccessDeniedException, JSONException, StorageClientException {

    final String contactUser = result.getPath().substring(
        result.getPath().lastIndexOf("/") + 1);
    if (contactUser == null) {
      throw new IllegalArgumentException("Missing " + User.NAME_FIELD);
    }
    renderContact(contactUser, request, writer, connectedUsers, processedUsers);
  }

  /**
   * @param request
   * @param writer
   * @param result
   * @param connectedUsers
   * @param processedUsers
   * @throws AccessDeniedException
   * @throws JSONException
   * @throws StorageClientException
   */
  protected void renderAuthorizable(final SlingHttpServletRequest request,
      final JSONWriter writer, final Result result, final List<String> connectedUsers,
      final Set<String> processedUsers) throws AccessDeniedException, JSONException,
      StorageClientException {

    renderContact(result.getPath(), request, writer, connectedUsers, processedUsers);
  }

  /**
   * Inspired by
   * {@link ConnectionFinderSearchResultProcessor#writeResult(SlingHttpServletRequest, JSONWriter, Result)}
   * 
   * @param user
   * @param request
   * @param writer
   * @param connectedUsers
   * @param processedUsers
   * @throws AccessDeniedException
   * @throws JSONException
   * @throws StorageClientException
   */
  protected void renderContact(final String user, final SlingHttpServletRequest request,
      final JSONWriter writer, final List<String> connectedUsers,
      final Set<String> processedUsers) throws AccessDeniedException, JSONException,
      StorageClientException {

    if (user == null) {
      throw new IllegalArgumentException("String user == null");
    }

    final Session session = StorageClientUtils.adaptToSession(request
        .getResourceResolver().adaptTo(javax.jcr.Session.class));
    if (!connectedUsers.contains(user) && !processedUsers.contains(user)
        && !session.getUserId().equals(user)) {
      final AuthorizableManager authMgr = session.getAuthorizableManager();
      final Authorizable auth = authMgr.findAuthorizable(user);

      if (auth != null) {
        writer.object();
        writer.key("target");
        writer.value(user);
        writer.key("profile");
        final BasicUserInfo basicUserInfo = new BasicUserInfo();
        ExtendedJSONWriter.writeValueMap(writer,
            new ValueMapDecorator(basicUserInfo.getProperties(auth)));
        writer.endObject();
        processedUsers.add(user);
      }
    }

  }

  /**
   * {@inheritDoc}
   * 
   * @see org.sakaiproject.nakamura.api.search.solr.SolrSearchBatchResultProcessor#getSearchResultSet(org.apache.sling.api.SlingHttpServletRequest,
   *      org.sakaiproject.nakamura.api.search.solr.Query)
   */
  public SolrSearchResultSet getSearchResultSet(SlingHttpServletRequest request,
      Query query) throws SolrSearchException {

    return searchServiceFactory.getSearchResultSet(request, query);
  }

}
