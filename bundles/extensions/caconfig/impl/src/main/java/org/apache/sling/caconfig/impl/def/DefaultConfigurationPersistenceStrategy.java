/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.caconfig.impl.def;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceUtil;
import org.apache.sling.caconfig.resource.impl.util.MapUtil;
import org.apache.sling.caconfig.resource.impl.util.PropertiesFilterUtil;
import org.apache.sling.caconfig.spi.ConfigurationCollectionPersistData;
import org.apache.sling.caconfig.spi.ConfigurationPersistData;
import org.apache.sling.caconfig.spi.ConfigurationPersistenceException;
import org.apache.sling.caconfig.spi.ConfigurationPersistenceStrategy;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The default persistence strategy is quite simple: directly use the configuration resources.
 * All existing properties are removed when new properties are stored in a singleton config resource.
 * All existing child resources are removed when a new configs are stored for collection config resources. 
 */
@Component(service = ConfigurationPersistenceStrategy.class)
@Designate(ocd=DefaultConfigurationPersistenceStrategy.Config.class)
public class DefaultConfigurationPersistenceStrategy implements ConfigurationPersistenceStrategy {

    @ObjectClassDefinition(name="Apache Sling Context-Aware Configuration Default Resource Persistence Strategy",
            description="Directly uses configuration resources for storing configuration data.")
    static @interface Config {
        
        @AttributeDefinition(name="Enabled",
                      description = "Enable this configuration resource persistence strategy.")
        boolean enabled() default true;

    }

    private volatile Config config;
    
    private static final Logger log = LoggerFactory.getLogger(DefaultConfigurationPersistenceStrategy.class);
    
    @Activate
    private void activate(ComponentContext componentContext, Config config) {
        this.config = config; 
    }
        
    @Override
    public Resource getResource(Resource resource) {
        if (!config.enabled()) {
            return null;
        }
        return resource;
    }

    @Override
    public String getResourcePath(String resourcePath) {
        if (!config.enabled()) {
            return null;
        }
        return resourcePath;
    }

    @Override
    public boolean persistConfiguration(ResourceResolver resourceResolver, String configResourcePath,
            ConfigurationPersistData data) {
        if (!config.enabled()) {
            return false;
        }
        getOrCreateResource(resourceResolver, configResourcePath, data.getProperties());
        commit(resourceResolver);
        return true;
    }

    @Override
    public boolean persistConfigurationCollection(ResourceResolver resourceResolver, String configResourceCollectionParentPath,
            ConfigurationCollectionPersistData data) {
        if (!config.enabled()) {
            return false;
        }
        Resource configResourceParent = getOrCreateResource(resourceResolver, configResourceCollectionParentPath, data.getProperties()); 
        
        // delete existing children and create new ones
        deleteChildren(configResourceParent);
        for (ConfigurationPersistData item : data.getItems()) {
            String path = configResourceParent.getPath() + "/" + item.getCollectionItemName();
            getOrCreateResource(resourceResolver, path, item.getProperties());
        }
        
        commit(resourceResolver);
        return true;
    }

    @Override
    public boolean deleteConfiguration(ResourceResolver resourceResolver, String configResourcePath) {
        if (!config.enabled()) {
            return false;
        }
        Resource resource = resourceResolver.getResource(configResourcePath);
        if (resource != null) {
            try {
                log.trace("! Delete resource {}", resource.getPath());
                resourceResolver.delete(resource);
            }
            catch (PersistenceException ex) {
                throw new ConfigurationPersistenceException("Unable to delete configuration at " + configResourcePath, ex);
            }
        }
        commit(resourceResolver);
        return true;
    }
    
    private Resource getOrCreateResource(ResourceResolver resourceResolver, String path, Map<String,Object> properties) {
        try {
            Resource resource = ResourceUtil.getOrCreateResource(resourceResolver, path, (String)null, (String)null, false);
            if (properties != null) {
                replaceProperties(resource, properties);
            }
            return resource;
        }
        catch (PersistenceException ex) {
            throw new ConfigurationPersistenceException("Unable to persist configuration to " + path, ex);
        }
    }

    private void deleteChildren(Resource resource) {
        ResourceResolver resourceResolver = resource.getResourceResolver();
        try {
            for (Resource child : resource.getChildren()) {
                log.trace("! Delete resource {}", child.getPath());
                resourceResolver.delete(child);
            }
        }
        catch (PersistenceException ex) {
            throw new ConfigurationPersistenceException("Unable to remove children from " + resource.getPath(), ex);
        }
    }
    
    private void replaceProperties(Resource resource, Map<String,Object> properties) {
        if (log.isTraceEnabled()) {
            log.trace("! Store properties for resource {}: {}", resource.getPath(), MapUtil.traceOutput(properties));
        }
        ModifiableValueMap modValueMap = resource.adaptTo(ModifiableValueMap.class);
        // remove all existing properties that are not filterd
        Set<String> propertyNamesToRemove = new HashSet<>(modValueMap.keySet());
        PropertiesFilterUtil.removeIgnoredProperties(propertyNamesToRemove);
        for (String propertyName : propertyNamesToRemove) {
            modValueMap.remove(propertyName);
        }
        modValueMap.putAll(properties);
    }
    
    private void commit(ResourceResolver resourceResolver) {
        try {
            resourceResolver.commit();
        }
        catch (PersistenceException ex) {
            throw new ConfigurationPersistenceException("Unable to save configuration: " + ex.getMessage(), ex);
        }
    }

}
