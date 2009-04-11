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
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.*;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.StringTokenizer;

import org.apache.felix.framework.util.SecureAction;
import org.osgi.service.url.URLConstants;
import org.osgi.service.url.URLStreamHandlerService;
import org.osgi.service.url.URLStreamHandlerSetter;

/**
 * <p>
 * This class implements a stream handler proxy. When the stream handler
 * proxy instance is created, it is associated with a particular protocol
 * and will answer all future requests for handling of that stream type. It
 * does not directly handle the stream handler requests, but delegates the
 * requests to an underlying stream handler service.
 * </p>
 * <p>
 * The proxy instance for a particular protocol is used for all framework
 * instances that may contain their own stream handler services. When
 * performing a stream handler operation, the proxy retrieves the handler
 * service from the framework instance associated with the current call
 * stack and delegates the call to the handler service.
 * </p>
 * <p>
 * The proxy will create simple stream handler service trackers for each
 * framework instance. The trackers will listen to service events in its
 * respective framework instance to maintain a reference to the "best"
 * stream handler service at any given time.
 * </p>
**/
public final class URLHandlersStreamHandlerProxy extends URLStreamHandler
    implements URLStreamHandlerSetter, InvocationHandler
{
    private static final Method EQUALS;
    private static final Method GET_DEFAULT_PORT;
    private static final Method GET_HOST_ADDRESS;
    private static final Method HASH_CODE;
    private static final Method HOSTS_EQUAL;
    private static final Method OPEN_CONNECTION;
    private static final Method SAME_FILE;
    private static final Method TO_EXTERNAL_FORM;
    
    static {
        try
        {
            SecureAction action = new SecureAction();
            EQUALS = URLStreamHandler.class.getDeclaredMethod("equals", 
                new Class[]{URL.class, URL.class});
            action.setAccesssible(EQUALS);
            GET_DEFAULT_PORT = URLStreamHandler.class.getDeclaredMethod("getDefaultPort", 
                (Class[]) null);
            action.setAccesssible(GET_DEFAULT_PORT);
            GET_HOST_ADDRESS = URLStreamHandler.class.getDeclaredMethod(
                    "getHostAddress", new Class[]{URL.class});
            action.setAccesssible(GET_HOST_ADDRESS);
            HASH_CODE = URLStreamHandler.class.getDeclaredMethod( 
                    "hashCode", new Class[]{URL.class});
            action.setAccesssible(HASH_CODE);
            HOSTS_EQUAL = URLStreamHandler.class.getDeclaredMethod(
                    "hostsEqual", new Class[]{URL.class, URL.class});
            action.setAccesssible(HOSTS_EQUAL);
            OPEN_CONNECTION = URLStreamHandler.class.getDeclaredMethod(
                    "openConnection", new Class[]{URL.class});
            action.setAccesssible(OPEN_CONNECTION);
            SAME_FILE = URLStreamHandler.class.getDeclaredMethod(
                    "sameFile", new Class[]{URL.class, URL.class});
            action.setAccesssible(SAME_FILE);
            TO_EXTERNAL_FORM = URLStreamHandler.class.getDeclaredMethod( 
                   "toExternalForm", new Class[]{URL.class});
            action.setAccesssible(TO_EXTERNAL_FORM);
        }
        catch (Exception ex)
        {
            ex.printStackTrace();
            throw new RuntimeException(ex.getMessage());
        }
    }
    
    private final Map m_trackerMap = new HashMap();
    private final String m_protocol;
    private final Object m_service;
    private final SecureAction m_action;
    private final URLStreamHandler m_builtIn;
    private final URL m_builtInURL;

    public URLHandlersStreamHandlerProxy(String protocol, 
        SecureAction action, URLStreamHandler builtIn, URL builtInURL)
    {
        m_protocol = protocol;
        m_service = null;
        m_action = action;
        m_builtIn = builtIn;
        m_builtInURL = builtInURL;
    }
    
    private URLHandlersStreamHandlerProxy(Object service, SecureAction action)
    {
        m_protocol = null;
        m_service = service;
        m_action = action;
        m_builtIn = null;
        m_builtInURL = null;
    }

    //
    // URLStreamHandler interface methods.
    //
    protected boolean equals(URL url1, URL url2)
    {
        Object svc = getStreamHandlerService();
        if (svc == null)
        {
            throw new IllegalStateException(
                "Unknown protocol: " + url1.getProtocol());
        }
        if (svc instanceof URLStreamHandlerService)
        {
            return ((URLStreamHandlerService) svc).equals(url1, url2);
        }
        try 
        {
            return ((Boolean) EQUALS.invoke(svc, new Object[]{url1, url2})).booleanValue();
        } 
        catch (Exception ex)  
        {
            ex.printStackTrace();
            throw new IllegalStateException("Stream handler unavailable due to: " + ex.getMessage());
        }
    }

    protected int getDefaultPort()
    {
        Object svc = getStreamHandlerService();
        if (svc == null)
        {
            throw new IllegalStateException("Stream handler unavailable.");
        }
        if (svc instanceof URLStreamHandlerService)
        {
            return ((URLStreamHandlerService) svc).getDefaultPort();
        }
        try 
        {
            return ((Integer) GET_DEFAULT_PORT.invoke(svc, null)).intValue();
        } 
        catch (Exception ex)  
        {
            ex.printStackTrace();
            throw new IllegalStateException("Stream handler unavailable due to: " + ex.getMessage());
        }
    }

    protected InetAddress getHostAddress(URL url)
    {
        Object svc = getStreamHandlerService();
        if (svc == null)
        {
            throw new IllegalStateException(
                "Unknown protocol: " + url.getProtocol());
        }
        if (svc instanceof URLStreamHandlerService)
        {
            return ((URLStreamHandlerService) svc).getHostAddress(url);
        }
        try 
        {
            return (InetAddress) GET_HOST_ADDRESS.invoke(svc, new Object[]{url});
        } 
        catch (Exception ex)  
        {
            ex.printStackTrace();
            throw new IllegalStateException("Stream handler unavailable due to: " + ex.getMessage());
        }
    }

    protected int hashCode(URL url)
    {
        Object svc = getStreamHandlerService();
        if (svc == null)
        {
            throw new IllegalStateException(
                "Unknown protocol: " + url.getProtocol());
        }
        if (svc instanceof URLStreamHandlerService)
        {
            return ((URLStreamHandlerService) svc).hashCode(url);
        }
        try 
        {
            return ((Integer) HASH_CODE.invoke(svc, new Object[]{url})).intValue();
        } 
        catch (Exception ex)  
        {
            ex.printStackTrace();
            throw new IllegalStateException("Stream handler unavailable due to: " + ex.getMessage());
        }
    }

    protected boolean hostsEqual(URL url1, URL url2)
    {
        Object svc = getStreamHandlerService();
        if (svc == null)
        {
            throw new IllegalStateException(
                "Unknown protocol: " + url1.getProtocol());
        }
        if (svc instanceof URLStreamHandlerService)
        {
            return ((URLStreamHandlerService) svc).hostsEqual(url1, url2);
        }
        try 
        {
            return ((Boolean) HOSTS_EQUAL.invoke(svc, new Object[]{url1, url2})).booleanValue();
        } 
        catch (Exception ex)  
        {
            ex.printStackTrace();
            throw new IllegalStateException("Stream handler unavailable due to: " + ex.getMessage());
        }
    }

    protected URLConnection openConnection(URL url) throws IOException
    {
        Object svc = getStreamHandlerService();
        if (svc == null)
        {
            throw new MalformedURLException("Unknown protocol: " + url.toString());
        }
        if (svc instanceof URLStreamHandlerService)
        {
            return ((URLStreamHandlerService) svc).openConnection(url);
        }
        try 
        {
            if ("http".equals(url.getProtocol()) &&
                "felix.extensions".equals(url.getHost()) &&
                9 == url.getPort())
            {
                try
                {
                    Object handler =  m_action.getDeclaredField(
                        ExtensionManager.class, "m_extensionManager", null);
    
                    if (handler != null)
                    {
                        return (URLConnection) m_action.invoke(
                            m_action.getMethod(handler.getClass(), 
                            "openConnection", new Class[]{URL.class}), handler, 
                            new Object[]{url});
                    }
                    
                    throw new IOException("Extensions not supported or ambiguous context.");
                }
                catch (Exception ex)
                {
                    throw new IOException(ex.getMessage());
                }
            }
            return (URLConnection) OPEN_CONNECTION.invoke(svc, new Object[]{url});
        } 
        catch (Exception ex)  
        {
            ex.printStackTrace();
            throw new IllegalStateException("Stream handler unavailable due to: " + ex.getMessage());
        }
    }

    protected void parseURL(URL url, String spec, int start, int limit)
    {
        Object svc = getStreamHandlerService();
        if (svc == null)
        {
            throw new IllegalStateException(
                "Unknown protocol: " + url.getProtocol());
        }
        if (svc instanceof URLStreamHandlerService)
        {
            ((URLStreamHandlerService) svc).parseURL(this, url, spec, start, limit);
        }
        else
        {
            try 
            {
                URL test = null;
                if (m_builtInURL != null)
                {
                    test = new URL(new URL(m_builtInURL, url.toExternalForm()), spec);
                }
                else
                {
                    test = m_action.createURL(url, spec, (URLStreamHandler) svc);
                }
                    
                super.setURL(url, test.getProtocol(), test.getHost(), test.getPort(),test.getAuthority(), 
                   test.getUserInfo(), test.getPath(), test.getQuery(), test.getRef());
            } 
            catch (Exception ex)  
            {
                ex.printStackTrace();
                throw new IllegalStateException("Stream handler unavailable due to: " + ex.getMessage());
            }
        }
    }

    protected boolean sameFile(URL url1, URL url2)
    {
        Object svc = getStreamHandlerService();
        if (svc == null)
        {
            throw new IllegalStateException(
                "Unknown protocol: " + url1.getProtocol());
        }
        if (svc instanceof URLStreamHandlerService)
        {
            return ((URLStreamHandlerService) svc).sameFile(url1, url2);
        }
        try 
        {
            return ((Boolean) SAME_FILE.invoke( 
                svc, new Object[]{url1, url2})).booleanValue();
        } 
        catch (Exception ex)  
        {
            ex.printStackTrace();
            throw new IllegalStateException("Stream handler unavailable due to: " + ex.getMessage());
        }
    }

    public void setURL(
        URL url, String protocol, String host, int port, String authority,
        String userInfo, String path, String query, String ref)
    {
        super.setURL(url, protocol, host, port, authority, userInfo, path, query, ref);
    }

    public void setURL(
        URL url, String protocol, String host, int port, String file, String ref)
    {
        super.setURL(url, protocol, host, port, null, null, file, null, ref);
    }

    protected String toExternalForm(URL url)
    {
        return toExternalForm(url, getStreamHandlerService());
    }
    
    private String toExternalForm(URL url, Object svc)
    {
        if (svc == null)
        {
            throw new IllegalStateException(
                "Unknown protocol: " + url.getProtocol());
        }
        if (svc instanceof URLStreamHandlerService)
        {
            return ((URLStreamHandlerService) svc).toExternalForm(url);
        }
        try 
        {
            return (String) TO_EXTERNAL_FORM.invoke( 
                svc, new Object[]{url});
        } 
        catch (Exception ex)  
        {
            ex.printStackTrace();
            throw new IllegalStateException("Stream handler unavailable due to: " + ex.getMessage());
        }
    }
    
    /**
     * <p>
     * Private method to retrieve the stream handler service from the
     * framework instance associated with the current call stack. A
     * simple service tracker is created and cached for the associated
     * framework instance when this method is called.
     * </p>
     * @return the stream handler service from the framework instance
     *         associated with the current call stack or <tt>null</tt>
     *         is no service is available.
    **/
    private Object getStreamHandlerService()
    {
        try
        {
            // Get the framework instance associated with call stack.
            Object framework = URLHandlers.getFrameworkFromContext();
    
            if (framework == null) 
            {
                return m_builtIn;
            }
    
            // Get the service tracker for the framework instance or create one.
            Object tracker;
            synchronized (m_trackerMap)
            {
                tracker = m_trackerMap.get(framework);
            }
            if (tracker == null)
            {
                // Create a filter for the protocol.
                String filter = 
                    "(&(objectClass="
                    + URLStreamHandlerService.class.getName()
                    + ")("
                    + URLConstants.URL_HANDLER_PROTOCOL
                    + "="
                    + m_protocol
                    + "))";
                // Create a simple service tracker for the framework.
                tracker = m_action.invoke(m_action.getConstructor(
                    framework.getClass().getClassLoader().loadClass(
                    "org.apache.felix.framework.URLHandlersServiceTracker"), 
                    new Class[]{framework.getClass().getClassLoader().loadClass(
                    "org.apache.felix.framework.Felix"), String.class}), 
                    new Object[]{framework, filter});

                // Cache the simple service tracker.
                synchronized (m_trackerMap) 
                {
                    if (!m_trackerMap.containsKey(framework))
                    {
                        m_trackerMap.put(framework, tracker);
                    }
                    else
                    {
                        unregister(tracker);
                        tracker = m_trackerMap.get(framework);
                    }
                }
            }
            Object service;
            if (tracker instanceof URLHandlersServiceTracker)
            {
                service = ((URLHandlersServiceTracker) tracker).getService();
            }
            else
            {
                service = m_action.invokeDirect(m_action.getMethod(
                    tracker.getClass(), "getService", null), tracker, null);
            }
            if (service == null) 
            {
                return m_builtIn;
            }
            if (service instanceof URLStreamHandlerService)
            {
                return (URLStreamHandlerService) service;
            }
            return (URLStreamHandlerService) Proxy.newProxyInstance(
                URLStreamHandlerService.class.getClassLoader(), 
                new Class[]{URLStreamHandlerService.class}, 
                new URLHandlersStreamHandlerProxy(service, m_action));
        }
        catch (ThreadDeath td)
        {
            throw td;
        }
        catch (Throwable t)
        {
         //   t.printStackTrace();
            return m_builtIn;
        }
    }

    void flush()
    {
        synchronized (m_trackerMap)
        {
            for (Iterator iter = m_trackerMap.values().iterator(); iter.hasNext();)
            {
                unregister(iter.next());
            }
            m_trackerMap.clear();
        }
    }

    private void unregister(Object tracker)
    {
        if (tracker instanceof URLHandlersServiceTracker)
        {
            ((URLHandlersServiceTracker) tracker).unregister();
        }
        else
        {
            try
            {
                m_action.invokeDirect(m_action.getMethod(tracker.getClass(), 
                    "unregister", null), tracker, null);
            }
            catch (Exception e)
            {
                // Not much we can do - log this or something
            }
        }
    }

    public Object invoke(Object obj, Method method, Object[] params)
        throws Throwable
    {
        try
        {
            Class[] types = method.getParameterTypes();
            if ("parseURL".equals(method.getName()))
            {
                types[0] = m_service.getClass().getClassLoader().loadClass(
                    URLStreamHandlerSetter.class.getName());
                params[0] = Proxy.newProxyInstance(
                    m_service.getClass().getClassLoader(), new Class[]{types[0]}, 
                    (URLHandlersStreamHandlerProxy) params[0]);
            }
            return m_action.invokeDirect(m_action.getMethod(m_service.getClass(), 
                method.getName(), types), m_service, params);
        } 
        catch (Exception ex)
        {
            throw ex;
        }
    }
}
