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
package org.apache.felix.framework.cache;

import java.io.*;

import java.util.Map;
import org.apache.felix.framework.Logger;
import org.osgi.framework.Bundle;

/**
 * <p>
 * This class is a logical abstraction for a bundle archive. This class,
 * combined with <tt>BundleCache</tt> and concrete <tt>BundleRevision</tt>
 * subclasses, implement the bundle cache for Felix. The bundle archive
 * abstracts the actual bundle content into revisions and the revisions
 * provide access to the actual bundle content. When a bundle is
 * installed it has one revision associated with its content. Updating a
 * bundle adds another revision for the updated content. Any number of
 * revisions can be associated with a bundle archive. When the bundle
 * (or framework) is refreshed, then all old revisions are purged and only
 * the most recent revision is maintained.
 * </p>
 * <p>
 * The content associated with a revision can come in many forms, such as
 * a standard JAR file or an exploded bundle directory. The bundle archive
 * is responsible for creating all revision instances during invocations
 * of the <tt>revise()</tt> method call. Internally, it determines the
 * concrete type of revision type by examining the location string as an
 * URL. Currently, it supports standard JAR files, referenced JAR files,
 * and referenced directories. Examples of each type of URL are, respectively:
 * </p>
 * <ul>
 *   <li><tt>http://www.foo.com/bundle.jar</tt></li>
 *   <li><tt>reference:file:/foo/bundle.jar</tt></li>
 *   <li><tt>reference:file:/foo/bundle/</tt></li>
 * </ul>
 * <p>
 * The "<tt>reference:</tt>" notation signifies that the resource should be
 * used "in place", meaning that they will not be copied. For referenced JAR
 * files, some resources may still be copied, such as embedded JAR files or
 * native libraries, but for referenced exploded bundle directories, nothing
 * will be copied. Currently, reference URLs can only refer to "file:" targets.
 * </p>
 * @see org.apache.felix.framework.cache.BundleCache
 * @see org.apache.felix.framework.cache.BundleRevision
**/
public class BundleArchive
{
    public static final transient String FILE_PROTOCOL = "file:";
    public static final transient String REFERENCE_PROTOCOL = "reference:";
    public static final transient String INPUTSTREAM_PROTOCOL = "inputstream:";

    private static final transient String BUNDLE_INFO_FILE = "bundle.info";
    private static final transient String REVISION_LOCATION_FILE = "revision.location";
    private static final transient String REVISION_DIRECTORY = "version";
    private static final transient String DATA_DIRECTORY = "data";

    private final Logger m_logger;
    private final Map m_configMap;
    private final File m_archiveRootDir;

    private long m_id = -1;
    private String m_originalLocation = null;
    private int m_persistentState = -1;
    private int m_startLevel = -1;
    private long m_lastModified = -1;
    private long m_refreshCount = -1;

    private BundleRevision[] m_revisions = null;

    /**
     * <p>
     * This constructor is only used by the system bundle archive implementation
     * because it is special an is not really an archive.
     * </p>
    **/
    public BundleArchive()
    {
        m_logger = null;
        m_configMap = null;
        m_archiveRootDir = null;
    }

    /**
     * <p>
     * This constructor is used for creating new archives when a bundle is
     * installed into the framework. Each archive receives a logger, a root
     * directory, its associated bundle identifier, the associated bundle
     * location string, and an input stream from which to read the bundle
     * content. The root directory is where any required state can be
     * stored. The input stream may be null, in which case the location is
     * used as an URL to the bundle content.
     * </p>
     * @param logger the logger to be used by the archive.
     * @param archiveRootDir the archive root directory for storing state.
     * @param id the bundle identifier associated with the archive.
     * @param location the bundle location string associated with the archive.
     * @param is input stream from which to read the bundle content.
     * @throws Exception if any error occurs.
    **/
    public BundleArchive(Logger logger, Map configMap, File archiveRootDir, long id,
        String location, int startLevel, InputStream is) throws Exception
    {
        m_logger = logger;
        m_configMap = configMap;
        m_archiveRootDir = archiveRootDir;
        m_id = id;
        if (m_id <= 0)
        {
            throw new IllegalArgumentException(
                "Bundle ID cannot be less than or equal to zero.");
        }
        m_originalLocation = location;
        m_persistentState = Bundle.INSTALLED;
        m_startLevel = startLevel;
        m_lastModified = System.currentTimeMillis();
        m_refreshCount = 0;

        // Save state.
        initialize();

        // Add a revision for the content.
        revise(false, m_originalLocation, is);
    }

    /**
     * <p>
     * This constructor is called when an archive for a bundle is being
     * reconstructed when the framework is restarted. Each archive receives
     * a logger, a root directory, and its associated bundle identifier.
     * The root directory is where any required state can be stored.
     * </p>
     * @param logger the logger to be used by the archive.
     * @param archiveRootDir the archive root directory for storing state.
     * @param configMap configMap for BundleArchive
     * @throws Exception if any error occurs.
    **/
    public BundleArchive(Logger logger, Map configMap, File archiveRootDir)
        throws Exception
    {
        m_logger = logger;
        m_configMap = configMap;
        m_archiveRootDir = archiveRootDir;

        // Read previously saved state.
        readBundleInfo();

        // Add a revision for each one that already exists in the file
        // system. The file system might contain more than one revision
        // if the bundle was updated in a previous session, but the
        // framework was not refreshed; this might happen if the framework
        // did not exit cleanly. We must create the existing revisions so
        // that they can be properly purged.
        int revisionCount = 0;
        while (true)
        {
            // Count the number of existing revision directories, which
            // will be in a directory named like:
            //     "${REVISION_DIRECTORY)${refresh-count}.${revision-count}"
            File revisionRootDir = new File(m_archiveRootDir,
                REVISION_DIRECTORY + getRefreshCount() + "." + revisionCount);
            if (!BundleCache.getSecureAction().fileExists(revisionRootDir))
            {
                break;
            }

            // Increment the revision count.
            revisionCount++;
        }

        // If there are multiple revisions in the file system, then create
        // an array that is big enough to hold all revisions minus one; the
        // call below to revise() will add the most recent revision. NOTE: We
        // do not actually need to add a real revision object for the older
        // revisions since they will be purged immediately on framework startup.
        if (revisionCount > 1)
        {
            m_revisions = new BundleRevision[revisionCount - 1];
        }

        // Add the revision object for the most recent revision. We first try
        // to read the location from the current revision - if that fails we
        // likely have an old bundle cache and read the location the old way.
        // The next revision will update the bundle cache.
        revise(true, getRevisionLocation(revisionCount - 1), null);
    }

    /**
     * <p>
     * Returns the bundle identifier associated with this archive.
     * </p>
     * @return the bundle identifier associated with this archive.
     * @throws Exception if any error occurs.
    **/
    public synchronized long getId() throws Exception
    {
        return m_id;
    }

    /**
     * <p>
     * Returns the location string associated with this archive.
     * </p>
     * @return the location string associated with this archive.
     * @throws Exception if any error occurs.
    **/
    public synchronized String getLocation() throws Exception
    {
        return m_originalLocation;
    }

    /**
     * <p>
     * Returns the persistent state of this archive. The value returned is
     * one of the following: <tt>Bundle.INSTALLED</tt>, <tt>Bundle.ACTIVE</tt>,
     * or <tt>Bundle.UNINSTALLED</tt>.
     * </p>
     * @return the persistent state of this archive.
     * @throws Exception if any error occurs.
    **/
    public synchronized int getPersistentState() throws Exception
    {
        return m_persistentState;
    }

    /**
     * <p>
     * Sets the persistent state of this archive. The value is
     * one of the following: <tt>Bundle.INSTALLED</tt>, <tt>Bundle.ACTIVE</tt>,
     * or <tt>Bundle.UNINSTALLED</tt>.
     * </p>
     * @param state the persistent state value to set for this archive.
     * @throws Exception if any error occurs.
    **/
    public synchronized void setPersistentState(int state) throws Exception
    {
        if (m_persistentState != state)
        {
            m_persistentState = state;
            writeBundleInfo();
        }
    }

    /**
     * <p>
     * Returns the start level of this archive.
     * </p>
     * @return the start level of this archive.
     * @throws Exception if any error occurs.
    **/
    public synchronized int getStartLevel() throws Exception
    {
        return m_startLevel;
    }

    /**
     * <p>
     * Sets the the start level of this archive this archive.
     * </p>
     * @param level the start level to set for this archive.
     * @throws Exception if any error occurs.
    **/
    public synchronized void setStartLevel(int level) throws Exception
    {
        if (m_startLevel != level)
        {
            m_startLevel = level;
            writeBundleInfo();
        }
    }

    /**
     * <p>
     * Returns the last modification time of this archive.
     * </p>
     * @return the last modification time of this archive.
     * @throws Exception if any error occurs.
    **/
    public synchronized long getLastModified() throws Exception
    {
        return m_lastModified;
    }

    /**
     * <p>
     * Sets the the last modification time of this archive.
     * </p>
     * @param lastModified The time of the last modification to set for
     *      this archive. According to the OSGi specification this time is
     *      set each time a bundle is installed, updated or uninstalled.
     *
     * @throws Exception if any error occurs.
    **/
    public synchronized void setLastModified(long lastModified) throws Exception
    {
        if (m_lastModified != lastModified)
        {
            m_lastModified = lastModified;
            writeBundleInfo();
        }
    }

    /**
     * <p>
     * Returns a <tt>File</tt> object corresponding to the data file
     * of the relative path of the specified string.
     * </p>
     * @return a <tt>File</tt> object corresponding to the specified file name.
     * @throws Exception if any error occurs.
    **/
    public synchronized File getDataFile(String fileName) throws Exception
    {
        // Do some sanity checking.
        if ((fileName.length() > 0) && (fileName.charAt(0) == File.separatorChar))
        {
            throw new IllegalArgumentException(
                "The data file path must be relative, not absolute.");
        }
        else if (fileName.indexOf("..") >= 0)
        {
            throw new IllegalArgumentException(
                "The data file path cannot contain a reference to the \"..\" directory.");
        }

        // Get bundle data directory.
        File dataDir = new File(m_archiveRootDir, DATA_DIRECTORY);
        // Create the data directory if necessary.
        if (!BundleCache.getSecureAction().fileExists(dataDir))
        {
            if (!BundleCache.getSecureAction().mkdir(dataDir))
            {
                throw new IOException("Unable to create bundle data directory.");
            }
        }

        // Return the data file.
        return new File(dataDir, fileName);
    }

    /**
     * <p>
     * Returns the number of revisions available for this archive.
     * </p>
     * @return tthe number of revisions available for this archive.
    **/
    public synchronized int getRevisionCount()
    {
        return (m_revisions == null) ? 0 : m_revisions.length;
    }

    /**
     * <p>
     * Returns the revision object for the specified revision.
     * </p>
     * @return the revision object for the specified revision.
    **/
    public synchronized BundleRevision getRevision(int i)
    {
        if ((i >= 0) && (i < getRevisionCount()))
        {
            return m_revisions[i];
        }
        return null;
    }

    /**
     * <p>
     * This method adds a revision to the archive. The revision is created
     * based on the specified location and/or input stream.
     * </p>
     * @param location the location string associated with the revision.
     * @throws Exception if any error occurs.
    **/
    public synchronized void revise(boolean isReload, String location, InputStream is)
        throws Exception
    {
        // If we have an input stream, then we have to use it
        // no matter what the update location is, so just ignore
        // the update location and set the location to be input
        // stream.
        if (is != null)
        {
            location = "inputstream:";
        }
        BundleRevision revision = createRevisionFromLocation(location, is);
        if (revision == null)
        {
            throw new Exception("Unable to revise archive.");
        }

        if (!isReload)
        {
            setRevisionLocation(location, (m_revisions == null) ? 0 : m_revisions.length);
        }

        // Add new revision to revision array.
        if (m_revisions == null)
        {
            m_revisions = new BundleRevision[] { revision };
        }
        else
        {
            BundleRevision[] tmp = new BundleRevision[m_revisions.length + 1];
            System.arraycopy(m_revisions, 0, tmp, 0, m_revisions.length);
            tmp[m_revisions.length] = revision;
            m_revisions = tmp;
        }
    }

    /**
     * <p>
     * This method undoes the previous revision to the archive; this method will
     * remove the latest revision from the archive. This method is only called
     * when there are problems during an update after the revision has been
     * created, such as errors in the update bundle's manifest. This method
     * can only be called if there is more than one revision, otherwise there
     * is nothing to undo.
     * </p>
     * @return true if the undo was a success false if there is no previous revision
     * @throws Exception if any error occurs.
     */
    public synchronized boolean rollbackRevise() throws Exception
    {
        // Can only undo the revision if there is more than one.
        if (getRevisionCount() <= 1)
        {
            return false;
        }

        try
        {
            m_revisions[m_revisions.length - 1].close();
        }
        catch(Exception ex)
        {
           m_logger.log(Logger.LOG_ERROR, getClass().getName() +
               ": Unable to dispose latest revision", ex);
        }

        File revisionDir = new File(m_archiveRootDir, REVISION_DIRECTORY +
            getRefreshCount() + "." + (m_revisions.length - 1));

        if (BundleCache.getSecureAction().fileExists(revisionDir))
        {
            BundleCache.deleteDirectoryTree(revisionDir);
        }

        BundleRevision[] tmp = new BundleRevision[m_revisions.length - 1];
        System.arraycopy(m_revisions, 0, tmp, 0, m_revisions.length - 1);
        m_revisions = tmp;

        return true;
    }

    private synchronized String getRevisionLocation(int revision) throws Exception
    {
        InputStream is = null;
        BufferedReader br = null;
        try
        {
            is = BundleCache.getSecureAction().getFileInputStream(new File(
                new File(m_archiveRootDir, REVISION_DIRECTORY +
                getRefreshCount() + "." + revision), REVISION_LOCATION_FILE));

            br = new BufferedReader(new InputStreamReader(is));
            return br.readLine();
        }
        finally
        {
            if (br != null) br.close();
            if (is != null) is.close();
        }
    }

    private synchronized void setRevisionLocation(String location, int revision) throws Exception
    {
        // Save current revision location.
        OutputStream os = null;
        BufferedWriter bw = null;
        try
        {
            os = BundleCache.getSecureAction()
                .getFileOutputStream(new File(
                    new File(m_archiveRootDir, REVISION_DIRECTORY +
                    getRefreshCount() + "." + revision), REVISION_LOCATION_FILE));
            bw = new BufferedWriter(new OutputStreamWriter(os));
            bw.write(location, 0, location.length());
        }
        finally
        {
            if (bw != null) bw.close();
            if (os != null) os.close();
        }
    }

    public synchronized void close()
    {
        // Get the current revision count.
        int count = getRevisionCount();
        for (int i = 0; i < count; i++)
        {
            // Dispose of the revision, but this might be null in certain
            // circumstances, such as if this bundle archive was created
            // for an existing bundle that was updated, but not refreshed
            // due to a system crash; see the constructor code for details.
            if (m_revisions[i] != null)
            {
                try
                {
                    m_revisions[i].close();
                }
                catch (Exception ex)
                {
                    m_logger.log(
                        Logger.LOG_ERROR,
                            "Unable to close revision - "
                            + m_revisions[i].getRevisionRootDir(), ex);
                }
            }
        }
    }

    /**
     * <p>
     * This method closes any revisions and deletes the bundle archive directory.
     * </p>
     * @throws Exception if any error occurs.
    **/
    public synchronized void closeAndDelete()
    {
        // Close the revisions and delete the archive directory.
        close();
        if (!BundleCache.deleteDirectoryTree(m_archiveRootDir))
        {
            m_logger.log(
                Logger.LOG_ERROR,
                "Unable to delete archive directory - " + m_archiveRootDir);
        }
    }

    /**
     * <p>
     * This method removes all old revisions associated with the archive
     * and keeps only the current revision.
     * </p>
     * @throws Exception if any error occurs.
    **/
    public synchronized void purge() throws Exception
    {
        // Close the revisions and then delete all but the current revision.
        // We don't delete it the current revision, because we want to rename it
        // to the new refresh level.
        close();
        long refreshCount = getRefreshCount();
        int count = getRevisionCount();
        File revisionDir = null;
        for (int i = 0; i < count - 1; i++)
        {
            revisionDir = new File(m_archiveRootDir, REVISION_DIRECTORY + refreshCount + "." + i);
            if (BundleCache.getSecureAction().fileExists(revisionDir))
            {
                BundleCache.deleteDirectoryTree(revisionDir);
            }
        }

        // Save the current revision location for use later when
        // we recreate the revision.
        String location = getRevisionLocation(count -1);

        // Increment the refresh count.
        setRefreshCount(refreshCount + 1);

        // Rename the current revision directory to be the zero revision
        // of the new refresh level.
        File currentDir = new File(m_archiveRootDir, REVISION_DIRECTORY + (refreshCount + 1) + ".0");
        revisionDir = new File(m_archiveRootDir, REVISION_DIRECTORY + refreshCount + "." + (count - 1));
        BundleCache.getSecureAction().renameFile(revisionDir, currentDir);

        // Null the revision array since they are all invalid now.
        m_revisions = null;
        // Finally, recreate the revision for the current location.
        BundleRevision revision = createRevisionFromLocation(location, null);
        // Create new revision array.
        m_revisions = new BundleRevision[] { revision };
    }

    /**
     * <p>
     * Initializes the bundle archive object by creating the archive
     * root directory and saving the initial state.
     * </p>
     * @throws Exception if any error occurs.
    **/
    private void initialize() throws Exception
    {
        OutputStream os = null;
        BufferedWriter bw = null;

        try
        {
            // If the archive directory exists, then we don't
            // need to initialize since it has already been done.
            if (BundleCache.getSecureAction().fileExists(m_archiveRootDir))
            {
                return;
            }

            // Create archive directory, if it does not exist.
            if (!BundleCache.getSecureAction().mkdir(m_archiveRootDir))
            {
                m_logger.log(
                    Logger.LOG_ERROR,
                    getClass().getName() + ": Unable to create archive directory.");
                throw new IOException("Unable to create archive directory.");
            }

            writeBundleInfo();
        }
        finally
        {
            if (bw != null) bw.close();
            if (os != null) os.close();
        }
    }

    /**
     * <p>
     * Creates a revision based on the location string and/or input stream.
     * </p>
     * @return the location string associated with this archive.
    **/
    private BundleRevision createRevisionFromLocation(String location, InputStream is)
        throws Exception
    {
        // The revision directory is named using the refresh count and
        // the revision count. The revision count is obvious, but the
        // refresh count is less obvious. This is necessary due to how
        // native libraries are handled in Java; needless to say, every
        // time a bundle is refreshed we must change the name of its
        // native libraries so that we can reload them. Thus, we use the
        // refresh counter as a way to change the name of the revision
        // directory to give native libraries new absolute names.
        File revisionRootDir = new File(m_archiveRootDir,
            REVISION_DIRECTORY + getRefreshCount() + "." + getRevisionCount());

        BundleRevision result = null;

        try
        {
            // Check if the location string represents a reference URL.
            if ((location != null) && location.startsWith(REFERENCE_PROTOCOL))
            {
                // Reference URLs only support the file protocol.
                location = location.substring(REFERENCE_PROTOCOL.length());
                if (!location.startsWith(FILE_PROTOCOL))
                {
                    throw new IOException("Reference URLs can only be files: " + location);
                }

                // Decode any URL escaped sequences.
                location = decode(location);

                // Make sure the referenced file exists.
                File file = new File(location.substring(FILE_PROTOCOL.length()));
                if (!BundleCache.getSecureAction().fileExists(file))
                {
                    throw new IOException("Referenced file does not exist: " + file);
                }

                // If the referenced file is a directory, then create a directory
                // revision; otherwise, create a JAR revision with the reference
                // flag set to true.
                if (BundleCache.getSecureAction().isFileDirectory(file))
                {
                    result = new DirectoryRevision(m_logger, m_configMap,
                        revisionRootDir, location);
                }
                else
                {
                    result = new JarRevision(m_logger, m_configMap, revisionRootDir,
                        location, true);
                }
            }
            else if (location.startsWith(INPUTSTREAM_PROTOCOL))
            {
                // Assume all input streams point to JAR files.
                result = new JarRevision(m_logger, m_configMap, revisionRootDir,
                    location, false, is);
            }
            else
            {
                // Anything else is assumed to be a URL to a JAR file.
                result = new JarRevision(m_logger, m_configMap, revisionRootDir,
                    location, false);
            }
        }
        catch (Exception ex)
        {
            if (BundleCache.getSecureAction().fileExists(revisionRootDir))
            {
                if (!BundleCache.deleteDirectoryTree(revisionRootDir))
                {
                    m_logger.log(
                        Logger.LOG_ERROR,
                        getClass().getName()
                            + ": Unable to delete revision directory - "
                            + revisionRootDir);
                }
            }
            throw ex;
        }

        return result;
    }

    // Method from Harmony java.net.URIEncoderDecoder (luni subproject)
    // used by URI to decode uri components.
    private static String decode(String s) throws UnsupportedEncodingException 
    {
        StringBuffer result = new StringBuffer();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < s.length();) 
        {
            char c = s.charAt(i);
            if (c == '%') 
            {
                out.reset();
                do 
                {
                    if ((i + 2) >= s.length()) 
                    {
                        throw new IllegalArgumentException(
                            "Incomplete % sequence at: " + i);
                    }
                    int d1 = Character.digit(s.charAt(i + 1), 16);
                    int d2 = Character.digit(s.charAt(i + 2), 16);
                    if ((d1 == -1) || (d2 == -1))
                    {
                        throw new IllegalArgumentException("Invalid % sequence ("
                            + s.substring(i, i + 3)
                            + ") at: " + String.valueOf(i));
                    }
                    out.write((byte) ((d1 << 4) + d2));
                    i += 3;
                }
                while ((i < s.length()) && (s.charAt(i) == '%'));
                result.append(out.toString("UTF-8"));
                continue;
            }
            result.append(c);
            i++;
        }
        return result.toString();
    }


    /**
     * This utility method is used to retrieve the current refresh
     * counter value for the bundle. This value is used when generating
     * the bundle revision directory name where native libraries are extracted.
     * This is necessary because Sun's JVM requires a one-to-one mapping
     * between native libraries and class loaders where the native library
     * is uniquely identified by its absolute path in the file system. This
     * constraint creates a problem when a bundle is refreshed, because it
     * gets a new class loader. Using the refresh counter to generate the name
     * of the bundle revision directory resolves this problem because each time
     * bundle is refresh, the native library will have a unique name.
     * As a result of the unique name, the JVM will then reload the
     * native library without a problem.
    **/
    private long getRefreshCount() throws Exception
    {
        return m_refreshCount;
    }

    /**
     * This utility method is used to retrieve the current refresh
     * counter value for the bundle. This value is used when generating
     * the bundle revision directory name where native libraries are extracted.
     * This is necessary because Sun's JVM requires a one-to-one mapping
     * between native libraries and class loaders where the native library
     * is uniquely identified by its absolute path in the file system. This
     * constraint creates a problem when a bundle is refreshed, because it
     * gets a new class loader. Using the refresh counter to generate the name
     * of the bundle revision directory resolves this problem because each time
     * bundle is refresh, the native library will have a unique name.
     * As a result of the unique name, the JVM will then reload the
     * native library without a problem.
    **/
    private void setRefreshCount(long count)
        throws Exception
    {
        if (m_refreshCount != count)
        {
            m_refreshCount = count;
            writeBundleInfo();
        }
    }

    private void readBundleInfo() throws Exception
    {
        File infoFile = new File(m_archiveRootDir, BUNDLE_INFO_FILE);

        // Read the bundle start level.
        InputStream is = null;
        BufferedReader br= null;
        try
        {
            is = BundleCache.getSecureAction()
                .getFileInputStream(infoFile);
            br = new BufferedReader(new InputStreamReader(is));

            // Read id.
            m_id = Long.parseLong(br.readLine());
            // Read location.
            m_originalLocation = br.readLine();
            // Read state.
            m_persistentState = Integer.parseInt(br.readLine());
            // Read start level.
            m_startLevel = Integer.parseInt(br.readLine());
            // Read last modified.
            m_lastModified = Long.parseLong(br.readLine());
            // Read refresh count.
            m_refreshCount = Long.parseLong(br.readLine());
        }
        catch (FileNotFoundException ex)
        {
            // If there wasn't an info file, then maybe this is an old-style
            // bundle cache, so try to read the files individually. We can
            // delete this eventually.
            m_id = readId();
            m_originalLocation = readLocation();
            m_persistentState = readPersistentState();
            m_startLevel = readStartLevel();
            m_lastModified = readLastModified();
            m_refreshCount = readRefreshCount();
        }
        finally
        {
            if (br != null) br.close();
            if (is != null) is.close();
        }
    }

    private void writeBundleInfo() throws Exception
    {
        // Write the bundle start level.
        OutputStream os = null;
        BufferedWriter bw = null;
        try
        {
            os = BundleCache.getSecureAction()
                .getFileOutputStream(new File(m_archiveRootDir, BUNDLE_INFO_FILE));
            bw = new BufferedWriter(new OutputStreamWriter(os));

            // Write id.
            String s = Long.toString(m_id);
            bw.write(s, 0, s.length());
            bw.newLine();
            // Write location.
            s = (m_originalLocation == null) ? "" : m_originalLocation;
            bw.write(s, 0, s.length());
            bw.newLine();
            // Write state.
            s = Integer.toString(m_persistentState);
            bw.write(s, 0, s.length());
            bw.newLine();
            // Write start level.
            s = Integer.toString(m_startLevel);
            bw.write(s, 0, s.length());
            bw.newLine();
            // Write last modified.
            s = Long.toString(m_lastModified);
            bw.write(s, 0, s.length());
            bw.newLine();
            // Write refresh count.
            s = Long.toString(m_refreshCount);
            bw.write(s, 0, s.length());
            bw.newLine();
        }
        catch (IOException ex)
        {
            m_logger.log(
                Logger.LOG_ERROR,
                getClass().getName() + ": Unable to cache bundle info - " + ex);
            throw ex;
        }
        finally
        {
            if (bw != null) bw.close();
            if (os != null) os.close();
        }
    }

    //
    // Deprecated bundle cache format to be deleted eventually.
    //

    private static final transient String BUNDLE_ID_FILE = "bundle.id";
    private static final transient String BUNDLE_LOCATION_FILE = "bundle.location";
    private static final transient String BUNDLE_STATE_FILE = "bundle.state";
    private static final transient String BUNDLE_START_LEVEL_FILE = "bundle.startlevel";
    private static final transient String BUNDLE_LASTMODIFIED_FILE = "bundle.lastmodified";
    private static final transient String REFRESH_COUNTER_FILE = "refresh.counter";

    private long readId() throws Exception
    {
        long id;

        InputStream is = null;
        BufferedReader br = null;
        try
        {
            is = BundleCache.getSecureAction()
                .getFileInputStream(new File(m_archiveRootDir, BUNDLE_ID_FILE));
            br = new BufferedReader(new InputStreamReader(is));
            id = Long.parseLong(br.readLine());
        }
        catch (FileNotFoundException ex)
        {
            // HACK: Get the bundle identifier from the archive root directory
            // name, which is of the form "bundle<id>" where <id> is the bundle
            // identifier numbers. This is a hack to deal with old archives that
            // did not save their bundle identifier, but instead had it passed
            // into them. Eventually, this can be removed.
            id = Long.parseLong(
                m_archiveRootDir.getName().substring(
                    BundleCache.BUNDLE_DIR_PREFIX.length()));
        }
        finally
        {
            if (br != null) br.close();
            if (is != null) is.close();
        }

        return id;
    }

    private String readLocation() throws Exception
    {
        InputStream is = null;
        BufferedReader br = null;
        try
        {
            is = BundleCache.getSecureAction()
                .getFileInputStream(new File(m_archiveRootDir, BUNDLE_LOCATION_FILE));
            br = new BufferedReader(new InputStreamReader(is));
            return br.readLine();
        }
        finally
        {
            if (br != null) br.close();
            if (is != null) is.close();
        }
    }

    private static final transient String ACTIVE_STATE = "active";
    private static final transient String STARTING_STATE = "starting";
    private static final transient String UNINSTALLED_STATE = "uninstalled";

    private int readPersistentState() throws Exception
    {
        int state = Bundle.INSTALLED;

        // Get bundle state file.
        File stateFile = new File(m_archiveRootDir, BUNDLE_STATE_FILE);

        // If the state file doesn't exist, then
        // assume the bundle was installed.
        if (BundleCache.getSecureAction().fileExists(stateFile))
        {
            // Read the bundle state.
            InputStream is = null;
            BufferedReader br = null;
            try
            {
                is = BundleCache.getSecureAction()
                    .getFileInputStream(stateFile);
                br = new BufferedReader(new InputStreamReader(is));
                String s = br.readLine();
                if ((s != null) && s.equals(ACTIVE_STATE))
                {
                    state = Bundle.ACTIVE;
                }
                else if ((s != null) && s.equals(STARTING_STATE))
                {
                    state = Bundle.STARTING;
                }
                else if ((s != null) && s.equals(UNINSTALLED_STATE))
                {
                    state = Bundle.UNINSTALLED;
                }
                else
                {
                    state = Bundle.INSTALLED;
                }
            }
            catch (Exception ex)
            {
                state = Bundle.INSTALLED;
            }
            finally
            {
                if (br != null) br.close();
                if (is != null) is.close();
            }
        }

        return state;
    }

    private int readStartLevel() throws Exception
    {
        int level = -1;

        // Get bundle start level file.
        File levelFile = new File(m_archiveRootDir, BUNDLE_START_LEVEL_FILE);

        // If the start level file doesn't exist, then
        // return an error.
        if (!BundleCache.getSecureAction().fileExists(levelFile))
        {
            level = -1;
        }
        else
        {
            // Read the bundle start level.
            InputStream is = null;
            BufferedReader br= null;
            try
            {
                is = BundleCache.getSecureAction()
                    .getFileInputStream(levelFile);
                br = new BufferedReader(new InputStreamReader(is));
                level = Integer.parseInt(br.readLine());
            }
            finally
            {
                if (br != null) br.close();
                if (is != null) is.close();
            }
        }
        return level;
    }

    private long readLastModified() throws Exception
    {
        long last = 0;

        InputStream is = null;
        BufferedReader br = null;
        try
        {
            is = BundleCache.getSecureAction()
                .getFileInputStream(new File(m_archiveRootDir, BUNDLE_LASTMODIFIED_FILE));
            br = new BufferedReader(new InputStreamReader(is));
            last = Long.parseLong(br.readLine());
        }
        catch (Exception ex)
        {
            last = 0;
        }
        finally
        {
            if (br != null) br.close();
            if (is != null) is.close();
        }

        return last;
    }

    private long readRefreshCount() throws Exception
    {
        long count = 0;

        InputStream is = null;
        BufferedReader br = null;
        try
        {
            is = BundleCache.getSecureAction()
                .getFileInputStream(new File(m_archiveRootDir, REFRESH_COUNTER_FILE));
            br = new BufferedReader(new InputStreamReader(is));
            count = Long.parseLong(br.readLine());
        }
        catch (Exception ex)
        {
            count = 0;
        }
        finally
        {
            if (br != null) br.close();
            if (is != null) is.close();
        }

        return count;
    }
}