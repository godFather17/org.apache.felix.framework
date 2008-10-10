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
package org.apache.felix.framework;

import java.io.IOException;
import java.net.URL;
import java.security.ProtectionDomain;
import java.util.*;

import org.apache.felix.moduleloader.IContentLoader;
import org.apache.felix.moduleloader.IModule;
import org.osgi.framework.*;

abstract class BundleInfo
{
    private Logger m_logger = null;
    private boolean m_removalPending = false;
    private IModule[] m_modules = null;
    private int m_state = 0;
    private BundleActivator m_activator = null;
    private BundleContext m_context = null;
    private Map m_cachedHeaders = new HashMap();
    private long m_cachedHeadersTimestamp;

    // Indicates whether the bundle is stale, meaning that it has
    // been refreshed and completely removed from the framework.
    private boolean m_stale = false;

    // Indicates whether the bundle is an extension, meaning that it is
    // installed as an extension bundle to the framework (i.e., can not be
    // removed or updated until a framework restart.
    private boolean m_extension = false;

    // Used for bundle locking.
    private int m_lockCount = 0;
    private Thread m_lockThread = null;

    public BundleInfo(Logger logger, IModule module)
    {
        m_logger = logger;
        m_modules = (module == null) ? new IModule[0] : new IModule[] { module };
        m_state = Bundle.INSTALLED;
        m_stale = false;
        m_activator = null;
        m_context = null;
    }

    public Logger getLogger()
    {
        return m_logger;
    }

    public synchronized boolean isRemovalPending()
    {
        return m_removalPending;
    }

    public synchronized void setRemovalPending(boolean removalPending)
    {
        m_removalPending = removalPending;
    }

    /**
     * Returns an array of all modules associated with the bundle represented by
     * this <tt>BundleInfo</tt> object. A module in the array corresponds to a
     * revision of the bundle's JAR file and is ordered from oldest to newest.
     * Multiple revisions of a bundle JAR file might exist if a bundle is
     * updated, without refreshing the framework. In this case, exports from
     * the prior revisions of the bundle JAR file are still offered; the
     * current revision will be bound to packages from the prior revision,
     * unless the packages were not offered by the prior revision. There is
     * no limit on the potential number of bundle JAR file revisions.
     * @return array of modules corresponding to the bundle JAR file revisions.
    **/
    public synchronized IModule[] getModules()
    {
        return m_modules;
    }

    /**
     * Determines if the specified module is associated with this bundle.
     * @param module the module to determine if it is associate with this bundle.
     * @return <tt>true</tt> if the specified module is in the array of modules
     *         associated with this bundle, <tt>false</tt> otherwise.
    **/
    public synchronized boolean hasModule(IModule module)
    {
        for (int i = 0; i < m_modules.length; i++)
        {
            if (m_modules[i] == module)
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the newest module, which corresponds to the last module
     * in the module array.
     * @return the newest module.
    **/
    public synchronized IModule getCurrentModule()
    {
        return m_modules[m_modules.length - 1];
    }

    /**
     * Add a module that corresponds to a new bundle JAR file revision for
     * the bundle associated with this <tt>BundleInfo</tt> object.
     * @param module the module to add.
    **/
    public synchronized void addModule(IModule module)
    {
        IModule[] dest = new IModule[m_modules.length + 1];
        System.arraycopy(m_modules, 0, dest, 0, m_modules.length);
        dest[m_modules.length] = module;
        m_modules = dest;
    }

    public abstract String getSymbolicName();

    public abstract long getBundleId();

    public abstract String getLocation();

    public abstract int getStartLevel(int defaultLevel);

    public abstract void setStartLevel(int i);

    public abstract Map getCurrentHeader();

    public Map getCurrentLocalizedHeader(String locale)
    {
        synchronized (m_cachedHeaders)
        {
            // If the bundle has been updated, clear the cached headers
            if (getLastModified() > m_cachedHeadersTimestamp)
            {
                m_cachedHeaders.clear();
            }
            else
            {
                // Check if headers for this locale have already been resolved
                if (m_cachedHeaders.containsKey(locale))
                {
                    return (Map) m_cachedHeaders.get(locale);
                }
            }
        }

        Map rawHeaders = getCurrentHeader();
        Map headers = new HashMap(rawHeaders.size());
        headers.putAll(rawHeaders);

        // Check to see if we actually need to localize anything
        boolean needsLocalization = false;
        for (Iterator it = headers.values().iterator(); it.hasNext(); )
        {
            if (((String) it.next()).startsWith("%"))
            {
                needsLocalization = true;
                break;
            }
        }

        if (!needsLocalization)
        {
            // If localization is not needed, just cache the headers and return them as-is
            // Not sure if this is useful
            updateHeaderCache(locale, headers);
            return headers;
        }

        // Do localization here and return the localized headers
        IContentLoader loader = this.getCurrentModule().getContentLoader();

        String basename = (String) headers.get(Constants.BUNDLE_LOCALIZATION);
        if (basename == null)
        {
            basename = Constants.BUNDLE_LOCALIZATION_DEFAULT_BASENAME;
        }

        // Create ordered list of files to load properties from
        List resourceList = createResourceList(basename, locale);

        // Create a merged props file with all available props for this locale
        Properties mergedProperties = new Properties();
        for (Iterator it = resourceList.iterator(); it.hasNext(); )
        {
            URL temp = loader.getResource(it.next() + ".properties");
            if (temp == null)
            {
                continue;
            }
            try
            {
                mergedProperties.load(temp.openConnection().getInputStream());
            }
            catch (IOException ex)
            {
                // File doesn't exist, just continue loop
            }
        }

        // Resolve all localized header entries
        for (Iterator it = headers.entrySet().iterator(); it.hasNext(); )
        {
            Map.Entry entry = (Map.Entry) it.next();
            String value = (String) entry.getValue();
            if (value.startsWith("%"))
            {
                String newvalue;
                String key = value.substring(value.indexOf("%") + 1);
                newvalue = mergedProperties.getProperty(key);
                if (newvalue==null)
                {
                    newvalue = key;
                }
                entry.setValue(newvalue);
            }
        }

        updateHeaderCache(locale, headers);
        return headers;
    }

    private void updateHeaderCache(String locale, Map localizedHeaders)
    {
        synchronized (m_cachedHeaders)
        {
            m_cachedHeaders.put(locale, localizedHeaders);
            m_cachedHeadersTimestamp = System.currentTimeMillis();
        }
    }

    private List createResourceList(String basename, String locale)
    {
        List result = new ArrayList(4);

        StringTokenizer tokens;
        StringBuffer tempLocale = new StringBuffer(basename);

        result.add(tempLocale.toString());

        if (locale.length() > 0)
        {
            tokens = new StringTokenizer(locale, "_");
            while (tokens.hasMoreTokens())
            {
                tempLocale.append("_").append(tokens.nextToken());
                result.add(tempLocale.toString());
            }
        }
        return result;
    }

    public synchronized int getState()
    {
        return m_state;
    }

    public synchronized void setState(int i)
    {
        m_state = i;
    }

    public abstract long getLastModified();

    public abstract void setLastModified(long l);

    public abstract int getPersistentState();

    public abstract void setPersistentStateInactive();

    public abstract void setPersistentStateActive();

    public abstract void setPersistentStateUninstalled();

    public synchronized BundleContext getBundleContext()
    {
        return m_context;
    }

    public synchronized void setBundleContext(BundleContext context)
    {
        m_context = context;
    }

    public synchronized BundleActivator getActivator()
    {
        return m_activator;
    }

    public synchronized void setActivator(BundleActivator activator)
    {
        m_activator = activator;
    }

    public synchronized boolean isStale()
    {
        return m_stale;
    }

    public synchronized void setStale()
    {
        m_stale = true;
    }

    public synchronized boolean isExtension()
    {
        return m_extension;
    }

    public synchronized void setExtension(boolean extension)
    {
        m_extension = extension;
    }

    //
    // Locking related methods.
    // NOTE: These methods are not synchronized because it is assumed they
    // will only ever be called when the caller is in a synchronized block.
    //

    public synchronized boolean isLockable()
    {
        return (m_lockCount == 0) || (m_lockThread == Thread.currentThread());
    }

    public synchronized void lock()
    {
        if ((m_lockCount > 0) && (m_lockThread != Thread.currentThread()))
        {
            throw new IllegalStateException("Bundle is locked by another thread.");
        }
        m_lockCount++;
        m_lockThread = Thread.currentThread();
    }

    public synchronized void unlock()
    {
        if (m_lockCount == 0)
        {
            throw new IllegalStateException("Bundle is not locked.");
        }
        if ((m_lockCount > 0) && (m_lockThread != Thread.currentThread()))
        {
            throw new IllegalStateException("Bundle is locked by another thread.");
        }
        m_lockCount--;
        if (m_lockCount == 0)
        {
            m_lockThread = null;
        }
    }

    public synchronized void syncLock(BundleInfo info)
    {
        m_lockCount = info.m_lockCount;
        m_lockThread = info.m_lockThread;
    }

    public synchronized void setProtectionDomain(ProtectionDomain pd)
    {
        getCurrentModule().getContentLoader().setSecurityContext(pd);
    }

    public synchronized ProtectionDomain getProtectionDomain()
    {
        ProtectionDomain pd = null;

        for (int i = m_modules.length - 1; (i >= 0) && (pd == null); i--)
        {
            pd = (ProtectionDomain)
                m_modules[i].getContentLoader().getSecurityContext();
        }

        return pd;
    }
}