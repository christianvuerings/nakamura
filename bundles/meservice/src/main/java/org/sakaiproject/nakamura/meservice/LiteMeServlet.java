/**
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
package org.sakaiproject.nakamura.meservice;

import static org.sakaiproject.nakamura.api.connections.ConnectionState.ACCEPTED;
import static org.sakaiproject.nakamura.api.connections.ConnectionState.INVITED;
import static org.sakaiproject.nakamura.api.connections.ConnectionState.PENDING;
import static org.sakaiproject.nakamura.api.search.solr.SolrSearchConstants.PARAMS_ITEMS_PER_PAGE;

import com.google.common.collect.ImmutableMap;

import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.sling.SlingServlet;
import org.apache.jackrabbit.util.ISO9075;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.apache.sling.commons.json.JSONException;
import org.apache.solr.client.solrj.util.ClientUtils;
import org.apache.solr.common.params.CommonParams;
import org.sakaiproject.nakamura.api.connections.ConnectionConstants;
import org.sakaiproject.nakamura.api.connections.ConnectionManager;
import org.sakaiproject.nakamura.api.doc.BindingType;
import org.sakaiproject.nakamura.api.doc.ServiceBinding;
import org.sakaiproject.nakamura.api.doc.ServiceDocumentation;
import org.sakaiproject.nakamura.api.doc.ServiceMethod;
import org.sakaiproject.nakamura.api.doc.ServiceParameter;
import org.sakaiproject.nakamura.api.doc.ServiceResponse;
import org.sakaiproject.nakamura.api.http.cache.DynamicContentResponseCache;
import org.sakaiproject.nakamura.api.lite.Session;
import org.sakaiproject.nakamura.api.lite.StorageClientException;
import org.sakaiproject.nakamura.api.lite.StorageClientUtils;
import org.sakaiproject.nakamura.api.lite.accesscontrol.AccessDeniedException;
import org.sakaiproject.nakamura.api.lite.authorizable.Authorizable;
import org.sakaiproject.nakamura.api.lite.authorizable.AuthorizableManager;
import org.sakaiproject.nakamura.api.lite.authorizable.Group;
import org.sakaiproject.nakamura.api.message.LiteMessagingService;
import org.sakaiproject.nakamura.api.message.MessagingException;
import org.sakaiproject.nakamura.api.search.solr.Query;
import org.sakaiproject.nakamura.api.search.solr.Result;
import org.sakaiproject.nakamura.api.search.solr.SolrSearchException;
import org.sakaiproject.nakamura.api.search.solr.SolrSearchResultSet;
import org.sakaiproject.nakamura.api.search.solr.SolrSearchServiceFactory;
import org.sakaiproject.nakamura.api.user.AuthorizableUtil;
import org.sakaiproject.nakamura.api.user.BasicUserInfoService;
import org.sakaiproject.nakamura.api.user.UserConstants;
import org.sakaiproject.nakamura.api.util.LocaleUtils;
import org.sakaiproject.nakamura.util.ExtendedJSONWriter;
import org.sakaiproject.nakamura.util.LitePersonalUtils;
import org.sakaiproject.nakamura.util.PathUtils;
import org.sakaiproject.nakamura.util.ServletUtils;
import org.sakaiproject.nakamura.util.telemetry.TelemetryCounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.MissingResourceException;
import java.util.Set;
import java.util.TimeZone;

import javax.jcr.RepositoryException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletResponse;

@ServiceDocumentation(name = "MeServlet", okForVersion = "1.2",
    shortDescription = "Returns information about the current active user.",
    description = "Presents information about current user in JSON format.",
    bindings = @ServiceBinding(type = BindingType.PATH, bindings = "/system/me"),
    methods = @ServiceMethod(name = "GET", description = "Get information about current user.",
        parameters = { @ServiceParameter(name="uid", description="If present the user id of the me feed to be returned")},
        response = {
    @ServiceResponse(code = 200, description = "Request for information was successful. <br />"
        + "A JSON representation of the current user is returned. E.g. for an anonymous user:"
        + "<pre>{\n" +
      "    \"user\": {\n" +
      "        \"anon\": true,\n" +
      "        \"subjects\": [],\n" +
      "        \"superUser\": false\n" +
      "    },\n" +
      "    \"profile\": {\n" +
      "        \"basic\": {\n" +
      "            \"access\": \"everybody\",\n" +
      "            \"elements\": {\n" +
      "                \"lastName\": {\n" +
      "                    \"value\": \"User\"\n" +
      "                },\n" +
      "                \"email\": {\n" +
      "                    \"value\": \"anon@sakai.invalid\"\n" +
      "                },\n" +
      "                \"firstName\": {\n" +
      "                    \"value\": \"Anonymous\"\n" +
      "                }\n" +
      "            }\n" +
      "        },\n" +
      "        \"rep:userId\": \"anonymous\"\n" +
      "    },\n" +
      "    \"messages\": {\n" +
      "        \"unread\": 0\n" +
      "    },\n" +
      "    \"contacts\": {},\n" +
      "    \"groups\": []\n" +
      "}<pre>"),
    @ServiceResponse(code = 401, description = "Unauthorized: credentials provided were not acceptable to return information for."),
    @ServiceResponse(code = 500, description = "Unable to return information about current user.") }))
@SlingServlet(paths = { "/system/me" }, generateComponent = false, methods = { "GET" })
@Component
public class LiteMeServlet extends SlingSafeMethodsServlet {
  private static final long serialVersionUID = -3786472219389695181L;
  private static final Logger LOG = LoggerFactory.getLogger(LiteMeServlet.class);

  @Reference
  protected transient LiteMessagingService messagingService;

  @Reference
  protected transient ConnectionManager connectionManager;

  @Reference
  protected SolrSearchServiceFactory searchServiceFactory;

  @Reference
  protected BasicUserInfoService basicUserInfoService;

  @Reference
  protected DynamicContentResponseCache dynamicContentResponseCache;

  @Reference
  protected transient LocaleUtils localeUtils;

  /**
   * {@inheritDoc}
   *
   * @see org.apache.sling.api.servlets.SlingSafeMethodsServlet#doGet(org.apache.sling.api.SlingHttpServletRequest,
   *      org.apache.sling.api.SlingHttpServletResponse)
   */
  @Override
  protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response)
      throws ServletException, IOException {
    if (dynamicContentResponseCache.send304WhenClientHasFreshETag(UserConstants.USER_RESPONSE_CACHE, request, response)) {
      return;
    }
    TelemetryCounter.incrementValue("meservice", "LiteMeServlet", "/system/me");
    try {
      response.setContentType("application/json");
      response.setCharacterEncoding("UTF-8");
      final Session session = StorageClientUtils.adaptToSession(request
          .getResourceResolver().adaptTo(javax.jcr.Session.class));
      if (session == null) {
        LOG.error("########## session is null");
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED,
        "Access denied error.");
        return;
      }
      AuthorizableManager um = session.getAuthorizableManager();
      String userId = session.getUserId();
      String requestedUserId = request.getParameter("uid");
      if ( requestedUserId != null && requestedUserId.length() > 0) {
        userId = requestedUserId;
      }
      Authorizable au = um.findAuthorizable(userId);
      if ( au == null ) {
        response.sendError(HttpServletResponse.SC_BAD_REQUEST,"User "+userId+" not found.");
        return;
      }
      PrintWriter w = response.getWriter();
      ExtendedJSONWriter writer = new ExtendedJSONWriter(w);
      writer.setTidy(ServletUtils.isTidy(request));
      writer.object();
      // User info
      writer.key("user");
      writeUserJSON(writer, session, au, request);

      // Dump this user his info
      writer.key("profile");
      ValueMap profile = new ValueMapDecorator(basicUserInfoService.getProperties(au));
      writer.valueMap(profile);

      // Dump this user his number of unread messages.
      writer.key("messages");
      writeMessageCounts(writer, session, au, request);

      // Dump this user his number of contacts.
      writer.key("contacts");
      writeContactCounts(writer, au, request);

      // Dump the groups for this user.
      writer.key("groups");
      writeGroups(writer, session, au);

      writer.endObject();

      dynamicContentResponseCache.recordResponse(UserConstants.USER_RESPONSE_CACHE, request, response);

    } catch (JSONException e) {
      LOG.error("Failed to create proper JSON response in /system/me", e);
      response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Failed to create proper JSON response.");
    } catch (StorageClientException e) {
      LOG.error("Failed to get a user his profile node in /system/me", e);
      response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Sparse storage client error.");
    } catch (AccessDeniedException e) {
      LOG.error("Failed to get a user his profile node in /system/me", e);
      response.sendError(HttpServletResponse.SC_UNAUTHORIZED,
          "Access denied error.");
    } catch (MessagingException e) {
      LOG.error("Failed to get a user his message counts in /system/me", e);
      response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Messaging error.");
    } catch (SolrSearchException e) {
      LOG.error("Failed to execute a Solr search in /system/me", e);
      response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Solr search error.");
    }

  }

  /**
   * @param writer
   * @param session
   * @param au
   * @param jcrSession
   * @throws JSONException
   * @throws StorageClientException
   * @throws AccessDeniedException
   * @throws RepositoryException
   */
  protected void writeGroups(ExtendedJSONWriter writer, Session session, Authorizable au)
      throws JSONException, StorageClientException, AccessDeniedException {
    AuthorizableManager authorizableManager = session.getAuthorizableManager();
    writer.array();
    if (!UserConstants.ANON_USERID.equals(au.getId())) {
      // KERN-1831 changed from getPrincipals to memberOf to drill down list
      for (Iterator<Group> memberOf = au.memberOf(authorizableManager); memberOf.hasNext(); ) {
//      this is the old code for outputting only direct memberships. might be needed later if such a flag is added.
//      String[] principals = au.getPrincipals();
//      for(String principal : principals) {
//        Authorizable group = authorizableManager.findAuthorizable(principal);
        Authorizable group = memberOf.next();
        if (AuthorizableUtil.isContactGroup(group)
            || Group.EVERYONE.equals(group.getId())) {
          // we don't want the "everyone" group or contact groups in this feed
          continue;
        }
        if (group.hasProperty(UserConstants.PROP_MANAGED_GROUP)) {
          // fetch the group that the manager group manages
          group = authorizableManager.findAuthorizable((String) group.getProperty(UserConstants.PROP_MANAGED_GROUP));
          if (group == null || !(group instanceof Group)) {
            continue;
          }
        }
        ValueMap groupProfile = new ValueMapDecorator(basicUserInfoService.getProperties(group));
        if (groupProfile != null) {
          writer.valueMap(groupProfile);
        }
      }
    }
    writer.endArray();
  }

  /**
   * Writes a JSON Object that contains the number of contacts for a user split up in
   * PENDING, ACCEPTED.
   *
   * @param writer
   * @param session
   * @param au
   * @throws JSONException
   * @throws SolrSearchException
   * @throws RepositoryException
   */
  protected void writeContactCounts(ExtendedJSONWriter writer, Authorizable au,
      SlingHttpServletRequest request) throws JSONException, SolrSearchException {
    writer.object();

    // We don't do queries for anonymous users. (Possible ddos hole).
    String userID = au.getId();
    if (UserConstants.ANON_USERID.equals(userID)) {
      writer.endObject();
      return;
    }

    // Get the path to the store for this user.
    Map<String, Integer> contacts = new HashMap<String, Integer>();
    contacts.put(ACCEPTED.toString().toLowerCase(), 0);
    contacts.put(INVITED.toString().toLowerCase(), 0);
    contacts.put(PENDING.toString().toLowerCase(), 0);
    try {
      // This could just use ConnectionUtils.getConnectionPathBase, but that util class is
      // in the private package unfortunately.
      String store = LitePersonalUtils.getHomePath(userID) + "/"
          + ConnectionConstants.CONTACT_STORE_NAME;
      store = ISO9075.encodePath(store);
      String queryString = "path:" + ClientUtils.escapeQueryChars(store) + " AND resourceType:sakai/contact AND state:(ACCEPTED OR INVITED OR PENDING)";
      Query query = new Query(queryString);
      LOG.debug("Submitting Query {} ", query);
      SolrSearchResultSet resultSet = searchServiceFactory.getSearchResultSet(
          request, query, false);
      Iterator<Result> resultIterator = resultSet.getResultSetIterator();
      while (resultIterator.hasNext()) {
        Result contact = resultIterator.next();
        if (contact.getProperties().containsKey("state")) {
          String state = ((String) contact.getProperties().get("state").iterator().next()).toLowerCase();
          int count = 0;
          if (contacts.containsKey(state)) {
            count = contacts.get(state);
          }
          contacts.put(state, count + 1);
        }
      }
    } finally {
      for (Entry<String, Integer> entry : contacts.entrySet()) {
        writer.key(entry.getKey());
        writer.value(entry.getValue());
      }
    }
    writer.endObject();
  }

  /**
   * Writes a JSON Object that contains the unread messages for a user.
   *
   * @param writer
   *          The writer
   * @param session
   *          A JCR session to perform queries with. This session needs read access on the
   *          authorizable's message box.
   * @param au
   *          An authorizable to look up the messages for.
   * @throws JSONException
   * @throws RepositoryException
   * @throws MessagingException
   * @throws SolrSearchException
   */
  protected void writeMessageCounts(ExtendedJSONWriter writer, Session session,
      Authorizable au, SlingHttpServletRequest request) throws JSONException, MessagingException, SolrSearchException {
    writer.object();
    writer.key("unread");

    // We don't do queries for anonymous users. (Possible ddos hole).
    String userID = au.getId();
    if (UserConstants.ANON_USERID.equals(userID)) {
      writer.value(0);
      writer.endObject();
      return;
    }

    long count = 0;
    try {
      String store = messagingService.getFullPathToStore(au.getId(), session);
      store = ISO9075.encodePath(store);
      String queryString = "messagestore:" + ClientUtils.escapeQueryChars(store) + " AND type:internal AND messagebox:inbox AND read:false";
      final Map<String, Object> queryOptions = ImmutableMap.of(
          PARAMS_ITEMS_PER_PAGE, (Object) "0",
          CommonParams.START, "0"
      );
      Query query = new Query(queryString, queryOptions);
      LOG.debug("Submitting Query {} ", query);
      SolrSearchResultSet resultSet = searchServiceFactory.getSearchResultSet(
          request, query, false);
      count = resultSet.getSize();
    } finally {
      writer.value(count);
    }
    writer.endObject();
  }

  /**
   *
   * @param write
   * @param session
   * @param authorizable
   * @throws RepositoryException
   * @throws JSONException
   * @throws StorageClientException
   */
  protected void writeUserJSON(ExtendedJSONWriter write, Session session,
      Authorizable authorizable, SlingHttpServletRequest request)
      throws JSONException, StorageClientException {

    String user = session.getUserId();
    boolean isAnonymous = (UserConstants.ANON_USERID.equals(user));
    if (isAnonymous || authorizable == null) {

      write.object();
      write.key("anon").value(true);
      write.key("subjects");
      write.array();
      write.endArray();
      write.key("superUser");
      write.value(false);
      write.endObject();
    } else {
      Set<String> subjects = getSubjects(authorizable, session.getAuthorizableManager());
      Map<String, Object> properties = localeUtils.getProperties(authorizable);

      write.object();
      writeGeneralInfo(write, authorizable, subjects, properties);
      writeLocale(write, properties, request);
      write.endObject();
    }

  }

  /**
   * Writes the local and timezone information.
   *
   * @param write
   * @param properties
   * @throws JSONException
   */
  protected void writeLocale(ExtendedJSONWriter write, Map<String, Object> properties,
      SlingHttpServletRequest request) throws JSONException {

    final Locale locale = localeUtils.getLocale(properties);
    final TimeZone tz = localeUtils.getTimeZone(properties);

    /* Add the locale information into the output */
    write.key("locale");
    write.object();
    write.key("country");
    write.value(locale.getCountry());
    write.key("displayCountry");
    write.value(locale.getDisplayCountry(locale));
    write.key("displayLanguage");
    write.value(locale.getDisplayLanguage(locale));
    write.key("displayName");
    write.value(locale.getDisplayName(locale));
    write.key("displayVariant");
    write.value(locale.getDisplayVariant(locale));
    write.key("ISO3Country");
    try {
      write.value(locale.getISO3Country());
    } catch (MissingResourceException e) {
      write.value("");
      LOG.debug("Unable to find ISO3 country [{}]", locale);
    }
    write.key("ISO3Language");
    try {
      write.value(locale.getISO3Language());
    } catch (MissingResourceException e) {
      write.value("");
      LOG.debug("Unable to find ISO3 language [{}]", locale);
    }
    write.key("language");
    write.value(locale.getLanguage());
    write.key("variant");
    write.value(locale.getVariant());

    /* Add the timezone information into the output */
    write.key("timezone");
    write.object();
    write.key("name");
    write.value(tz.getID());
    write.key("GMT");
    write.value(localeUtils.getOffset(tz));
    write.endObject();

    write.endObject();
  }

  /**
   * Writes the general information about a user such as the userid, storagePrefix, wether
   * he is a superUser or not..
   *
   * @param write
   * @param user
   * @param session
   * @throws JSONException
   * @throws RepositoryException
   */
  protected void writeGeneralInfo(ExtendedJSONWriter write, Authorizable user,
      Set<String> subjects, Map<String, Object> properties) throws JSONException {

    write.key("userid").value(user.getId());
    write.key("userStoragePrefix");
    // For backwards compatibility we substring the first slash out and append one at the
    // back.
    write.value("~" + user.getId() + "/");
    write.key("userProfilePath");
    write.value(PathUtils.translateAuthorizablePath(LitePersonalUtils.getProfilePath(user.getId())));
    write.key("superUser");
    write.value(subjects.contains("administrators"));
    write.key("properties");
    ValueMap jsonProperties = new ValueMapDecorator(properties);
    write.valueMap(jsonProperties);
    write.key("subjects");
    write.array();
    for (String groupName : subjects) {
      write.value(groupName);
    }
    write.endArray();
  }

  /**
   * All the names of the {@link Group groups} a user is a member of.
   *
   * @param authorizable
   *          The {@link Authorizable authorizable} that represents the user.
   * @param authorizableManager
   *          The {@link AuthorizableManager authorizableManager} that can be used to retrieve
   *          the group membership.
   * @return All the names of the {@link Group groups} a user is a member of.
   * @throws RepositoryException
   */
  protected Set<String> getSubjects(Authorizable authorizable,
      AuthorizableManager authorizableManager) {
    Set<String> subjects = new HashSet<String>();
    if (authorizable != null) {
      String principal = authorizable.getId();
      if (principal != null) {
        Iterator<Group> it = authorizable.memberOf(authorizableManager);
        while (it.hasNext()) {
          Group aGroup = it.next();
          if (!aGroup.getId().equals(Group.EVERYONE)) {
            subjects.add(aGroup.getId());
          }
        }
      }
    }
    return subjects;
  }

}
