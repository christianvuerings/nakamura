package org.sakaiproject.nakamura.templates.velocity;

import org.apache.commons.collections.ExtendedProperties;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.apache.sling.api.request.RequestParameter;
import org.apache.sling.api.request.RequestParameterMap;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.osgi.service.component.ComponentContext;
import org.sakaiproject.nakamura.api.templates.TemplateNodeSource;
import org.sakaiproject.nakamura.api.templates.TemplateService;

import javax.jcr.Node;
import javax.jcr.Repository;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.*;

@Service
@Component(immediate = true)
public class VelocityTemplateService implements TemplateService, TemplateNodeSource {

  private VelocityEngine velocityEngine;

  private ThreadLocal<Node> boundNode = new ThreadLocal<Node>();

  @Reference
  protected Repository repository;

  public String evaluateTemplate(Map parameters, String template) {
    Map<String, String> sanitizedParameters = sanitize(parameters);
    VelocityContext context = new VelocityContext(sanitizedParameters);
    // combine template with parameter map
    Reader templateReader = new StringReader(template);
    StringWriter templateWriter = new StringWriter();
    try {
      velocityEngine.evaluate(context, templateWriter, "templateprocessing", templateReader);
    } catch (IOException e) {
      e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
    }
    return templateWriter.toString();
  }

  private Map<String,String> sanitize(Map parameters) {
    Map<String,String> rv = new HashMap<String,String>();
    for (Object key : parameters.keySet()) {
      Object value = parameters.get(key);
      if (value instanceof RequestParameter) {
        rv.put(key.toString(), ((RequestParameter) value).getString());
      } else if (value instanceof String[]) {
        String[] values = (String[])value;
        rv.put(key.toString(), values[0]);
      } else {
        rv.put(key.toString(), value.toString());
      }
    }
    return rv;
  }

  public Collection<String> missingTerms(Map parameters, String template) {
          Collection<String> missingTerms = new ArrayList<String>();
          int startPosition = template.indexOf("${");
          while(startPosition > -1) {
            int endPosition = template.indexOf("}", startPosition);
            if (endPosition > -1) {
              String key = template.substring(startPosition + 2, endPosition);
              Object value = parameters.get(key);
              if (value == null) {
                missingTerms.add(key);
              }
              // look for the next velocity replacement variable
              startPosition = template.indexOf("${", endPosition);
            } else {
              break;
            }
          }
         return missingTerms;
  }

  @SuppressWarnings("unchecked")
  protected void activate(ComponentContext ctx) throws Exception {
    velocityEngine = new VelocityEngine();
    velocityEngine.setProperty(VelocityEngine.RUNTIME_LOG_LOGSYSTEM, new VelocityLogger(this.getClass()));

    velocityEngine.setProperty(VelocityEngine.RESOURCE_LOADER, "jcr");
    velocityEngine.setProperty("jcr.resource.loader.class", JcrResourceLoader.class.getName());
    ExtendedProperties configuration = new ExtendedProperties();
    configuration.addProperty("jcr.resource.loader.resourceSource", this);
    velocityEngine.setExtendedProperties(configuration);
    velocityEngine.init();
  }

  public Node getNode() {
    // Velocity calls this, but it doesn't seem to make a difference in practice
    return null;
  }
}
