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

import java.util.*;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

class SystemBundleActivator implements BundleActivator
{
    private Felix m_felix = null;
    private List m_activatorList = null;
    private BundleContext m_context = null;
    private Map m_activatorContextMap = null;

    SystemBundleActivator(Felix felix, List activatorList)
    {
        this.m_felix = felix;
        this.m_activatorList = activatorList;
    }

    public void start(BundleContext context) throws Exception
    {
        this.m_context = context;

        // Start all activators.
        if (m_activatorList != null)
        {
            for (int i = 0; i < m_activatorList.size(); i++)
            {
                ((BundleActivator) m_activatorList.get(i)).start(context);
            }
        }
    }

    public void stop(BundleContext context) throws Exception
    {
        if (m_activatorList != null)
        {
            // Stop all activators.
            for (int i = 0; i < m_activatorList.size(); i++)
            {
                if ((m_activatorContextMap != null) &&
                    m_activatorContextMap.containsKey(m_activatorList.get(i)))
                {
                    ((BundleActivator) m_activatorList.get(i)).stop(
                        (BundleContext) m_activatorContextMap.get(
                        m_activatorList.get(i)));
                }
                else
                {
                    ((BundleActivator) m_activatorList.get(i)).stop(context);
                }
            }
        }
    }

    public BundleContext getBundleContext()
    {
        return m_context;
    }

    void addActivator(BundleActivator activator, BundleContext context) throws Exception
    {
        if (m_activatorList == null)
        {
            m_activatorList = new ArrayList();
        }

        m_activatorList.add(activator);

        if (context != null)
        {
            if (m_activatorContextMap == null)
            {
                m_activatorContextMap = new HashMap();
            }

            m_activatorContextMap.put(activator, context);

            activator.start(context);
        }
        else if (m_context != null)
        {
            activator.start(m_context);
        }
    }
}