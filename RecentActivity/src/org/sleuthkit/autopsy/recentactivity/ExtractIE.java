/*
 *
 * Autopsy Forensic Browser
 *
 * Copyright 2012-2021 Basis Technology Corp.
 *
 * Copyright 2012 42six Solutions.
 * Contact: aebadirad <at> 42six <dot> com
 * Project Contact/Architect: carrier <at> sleuthkit <dot> org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.sleuthkit.autopsy.recentactivity;

import java.io.BufferedReader;
import org.openide.util.NbBundle;
import org.sleuthkit.autopsy.coreutils.ExecUtil;
import org.sleuthkit.autopsy.coreutils.NetworkUtils;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Paths;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import org.sleuthkit.autopsy.coreutils.Logger;
import java.util.Collection;
import java.util.Scanner;
import java.util.stream.Collectors;
import org.openide.modules.InstalledFileLocator;
import org.openide.util.NbBundle.Messages;
import org.sleuthkit.autopsy.casemodule.Case;
import org.sleuthkit.autopsy.casemodule.services.FileManager;
import org.sleuthkit.autopsy.datamodel.ContentUtils;
import org.sleuthkit.datamodel.BlackboardArtifact;
import org.sleuthkit.datamodel.BlackboardArtifact.ARTIFACT_TYPE;
import org.sleuthkit.datamodel.BlackboardAttribute;
import org.sleuthkit.datamodel.BlackboardAttribute.ATTRIBUTE_TYPE;
import org.sleuthkit.datamodel.Content;
import org.sleuthkit.autopsy.coreutils.PlatformUtil;
import org.sleuthkit.autopsy.ingest.DataSourceIngestModuleProcessTerminator;
import org.sleuthkit.autopsy.ingest.DataSourceIngestModuleProgress;
import org.sleuthkit.autopsy.ingest.IngestJobContext;
import org.sleuthkit.datamodel.AbstractFile;
import static org.sleuthkit.datamodel.BlackboardArtifact.ARTIFACT_TYPE.TSK_WEB_HISTORY;
import org.sleuthkit.datamodel.ReadContentInputStream;
import org.sleuthkit.datamodel.TskCoreException;

/**
 * Extracts activity from Internet Explorer browser, as well as recent documents
 * in windows.
 */
class ExtractIE extends Extract {

    private static final Logger logger = Logger.getLogger(ExtractIE.class.getName());
    private String PASCO_LIB_PATH;
    private final String JAVA_PATH;
    private static final String RESOURCE_URL_PREFIX = "res://";
    private static final SimpleDateFormat dateFormatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
    private Content dataSource;
    private IngestJobContext context;

    @Messages({
        "Progress_Message_IE_History=IE History",
        "Progress_Message_IE_Bookmarks=IE Bookmarks",
        "Progress_Message_IE_Cookies=IE Cookies",
        "Progress_Message_IE_Downloads=IE Downloads",
        "Progress_Message_IE_FormHistory=IE Form History",
        "Progress_Message_IE_AutoFill=IE Auto Fill",
        "Progress_Message_IE_Logins=IE Logins",})

    ExtractIE() {
        super(NbBundle.getMessage(ExtractIE.class, "ExtractIE.moduleName.text"));
        JAVA_PATH = PlatformUtil.getJavaPath();
    }

    @Override
    public void process(Content dataSource, IngestJobContext context, DataSourceIngestModuleProgress progressBar) {
        String moduleTempDir = RAImageIngestModule.getRATempPath(getCurrentCase(), "IE", context.getJobId());
        String moduleTempResultsDir = Paths.get(moduleTempDir, "results").toString();
                
        this.dataSource = dataSource;
        this.context = context;
        dataFound = false;

        progressBar.progress(Bundle.Progress_Message_IE_Bookmarks());
        this.getBookmark();
        
        if (context.dataSourceIngestIsCancelled()) {
            return;
        }

        progressBar.progress(Bundle.Progress_Message_IE_Cookies());
        this.getCookie();
        
        if (context.dataSourceIngestIsCancelled()) {
            return;
        }

        progressBar.progress(Bundle.Progress_Message_IE_History());
        this.getHistory(moduleTempDir, moduleTempResultsDir);
    }

    /**
     * Finds the files storing bookmarks and creates artifacts
     */
    private void getBookmark() {
        org.sleuthkit.autopsy.casemodule.services.FileManager fileManager = currentCase.getServices().getFileManager();
        List<AbstractFile> favoritesFiles;
        try {
            favoritesFiles = fileManager.findFiles(dataSource, "%.url", "Favorites"); //NON-NLS
        } catch (TskCoreException ex) {
            logger.log(Level.WARNING, "Error fetching 'url' files for Internet Explorer bookmarks.", ex); //NON-NLS
            this.addErrorMessage(
                    NbBundle.getMessage(this.getClass(), "ExtractIE.getBookmark.errMsg.errGettingBookmarks",
                            this.getName()));
            return;
        }

        if (favoritesFiles.isEmpty()) {
            logger.log(Level.INFO, "Didn't find any IE bookmark files."); //NON-NLS
            return;
        }

        dataFound = true;
        Collection<BlackboardArtifact> bbartifacts = new ArrayList<>();
        for (AbstractFile fav : favoritesFiles) {
            if (fav.getSize() == 0) {
                continue;
            }

            if (context.dataSourceIngestIsCancelled()) {
                break;
            }

            String url = getURLFromIEBookmarkFile(fav);

            String name = fav.getName();
            Long datetime = fav.getCrtime();
            String Tempdate = datetime.toString();
            datetime = Long.valueOf(Tempdate);
            String domain = extractDomain(url);

            Collection<BlackboardAttribute> bbattributes = new ArrayList<>();
            bbattributes.add(new BlackboardAttribute(ATTRIBUTE_TYPE.TSK_URL,
                    RecentActivityExtracterModuleFactory.getModuleName(), url));
            bbattributes.add(new BlackboardAttribute(ATTRIBUTE_TYPE.TSK_TITLE,
                    RecentActivityExtracterModuleFactory.getModuleName(), name));
            bbattributes.add(new BlackboardAttribute(ATTRIBUTE_TYPE.TSK_DATETIME_CREATED,
                    RecentActivityExtracterModuleFactory.getModuleName(), datetime));
            bbattributes.add(new BlackboardAttribute(ATTRIBUTE_TYPE.TSK_PROG_NAME,
                    RecentActivityExtracterModuleFactory.getModuleName(),
                    NbBundle.getMessage(this.getClass(), "ExtractIE.moduleName.text")));
            if (domain != null && domain.isEmpty() == false) {
                bbattributes.add(new BlackboardAttribute(ATTRIBUTE_TYPE.TSK_DOMAIN,
                        RecentActivityExtracterModuleFactory.getModuleName(), domain));
            }

            try {
                bbartifacts.add(createArtifactWithAttributes(ARTIFACT_TYPE.TSK_WEB_BOOKMARK, fav, bbattributes));
            } catch (TskCoreException ex) {
                logger.log(Level.SEVERE, String.format("Failed to create %s for file %d",ARTIFACT_TYPE.TSK_WEB_BOOKMARK.getDisplayName(), fav.getId() ), ex);
            }
        }

        if(!context.dataSourceIngestIsCancelled()) {
            postArtifacts(bbartifacts);
        }
    }

    private String getURLFromIEBookmarkFile(AbstractFile fav) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(new ReadContentInputStream(fav)));
        String line, url = "";
        try {
            line = reader.readLine();
            while (null != line) {
                // The actual shortcut line we are interested in is of the
                // form URL=http://path/to/website
                if (line.startsWith("URL")) { //NON-NLS
                    url = line.substring(line.indexOf("=") + 1);
                    break;
                }
                line = reader.readLine();
            }
        } catch (IOException ex) {
            logger.log(Level.WARNING, "Failed to read from content: " + fav.getName(), ex); //NON-NLS
            this.addErrorMessage(
                    NbBundle.getMessage(this.getClass(), "ExtractIE.getURLFromIEBmkFile.errMsg", this.getName(),
                            fav.getName()));
        } catch (IndexOutOfBoundsException ex) {
            logger.log(Level.WARNING, "Failed while getting URL of IE bookmark. Unexpected format of the bookmark file: " + fav.getName(), ex); //NON-NLS
            this.addErrorMessage(
                    NbBundle.getMessage(this.getClass(), "ExtractIE.getURLFromIEBmkFile.errMsg2", this.getName(),
                            fav.getName()));
        } finally {
            try {
                reader.close();
            } catch (IOException ex) {
                logger.log(Level.WARNING, "Failed to close reader.", ex); //NON-NLS
            }
        }

        return url;
    }

    /**
     * Finds files that store cookies and adds artifacts for them.
     */
    private void getCookie() {
        org.sleuthkit.autopsy.casemodule.services.FileManager fileManager = currentCase.getServices().getFileManager();
        List<AbstractFile> cookiesFiles;
        try {
            cookiesFiles = fileManager.findFiles(dataSource, "%.txt", "Cookies"); //NON-NLS
        } catch (TskCoreException ex) {
            logger.log(Level.WARNING, "Error getting cookie files for IE"); //NON-NLS
            this.addErrorMessage(
                    NbBundle.getMessage(this.getClass(), "ExtractIE.getCookie.errMsg.errGettingFile", this.getName()));
            return;
        }

        if (cookiesFiles.isEmpty()) {
            logger.log(Level.INFO, "Didn't find any IE cookies files."); //NON-NLS
            return;
        }

        dataFound = true;
        Collection<BlackboardArtifact> bbartifacts = new ArrayList<>();
        for (AbstractFile cookiesFile : cookiesFiles) {
            if (context.dataSourceIngestIsCancelled()) {
                break;
            }
            if (cookiesFile.getSize() == 0) {
                continue;
            }

            byte[] t = new byte[(int) cookiesFile.getSize()];
            try {
                final int bytesRead = cookiesFile.read(t, 0, cookiesFile.getSize());
            } catch (TskCoreException ex) {
                logger.log(Level.WARNING, "Error reading bytes of Internet Explorer cookie.", ex); //NON-NLS
                this.addErrorMessage(
                        NbBundle.getMessage(this.getClass(), "ExtractIE.getCookie.errMsg.errReadingIECookie",
                                this.getName(), cookiesFile.getName()));
                continue;
            }
            String cookieString = new String(t);
            String[] values = cookieString.split("\n");
            String url = values.length > 2 ? values[2] : "";
            String value = values.length > 1 ? values[1] : "";
            String name = values.length > 0 ? values[0] : "";
            Long datetime = cookiesFile.getCrtime();
            String tempDate = datetime.toString();
            datetime = Long.valueOf(tempDate);
            String domain = extractDomain(url);

            Collection<BlackboardAttribute> bbattributes = new ArrayList<>();
            bbattributes.add(new BlackboardAttribute(ATTRIBUTE_TYPE.TSK_URL,
                    RecentActivityExtracterModuleFactory.getModuleName(), url));
            bbattributes.add(new BlackboardAttribute(ATTRIBUTE_TYPE.TSK_DATETIME_CREATED,
                    RecentActivityExtracterModuleFactory.getModuleName(), datetime));
            bbattributes.add(new BlackboardAttribute(ATTRIBUTE_TYPE.TSK_NAME,
                    RecentActivityExtracterModuleFactory.getModuleName(), (name != null) ? name : ""));
            bbattributes.add(new BlackboardAttribute(ATTRIBUTE_TYPE.TSK_VALUE,
                    RecentActivityExtracterModuleFactory.getModuleName(), value));
            bbattributes.add(new BlackboardAttribute(ATTRIBUTE_TYPE.TSK_PROG_NAME,
                    RecentActivityExtracterModuleFactory.getModuleName(),
                    NbBundle.getMessage(this.getClass(), "ExtractIE.moduleName.text")));
            if (domain != null && domain.isEmpty() == false) {
                bbattributes.add(new BlackboardAttribute(ATTRIBUTE_TYPE.TSK_DOMAIN,
                        RecentActivityExtracterModuleFactory.getModuleName(), domain));
            }

            try {
                bbartifacts.add(createArtifactWithAttributes(ARTIFACT_TYPE.TSK_WEB_COOKIE, cookiesFile, bbattributes));
            } catch (TskCoreException ex) {
                logger.log(Level.SEVERE, String.format("Failed to create %s for file %d",ARTIFACT_TYPE.TSK_WEB_COOKIE.getDisplayName(), cookiesFile.getId() ), ex);
            }
        }

        if(!context.dataSourceIngestIsCancelled()) {
            postArtifacts(bbartifacts);
        }
    }

    /**
     * Locates index.dat files, runs Pasco on them, and creates artifacts.
     * @param moduleTempDir The path to the module temp directory.
     * @param moduleTempResultsDir The path to the module temp results directory.
     */
    private void getHistory(String moduleTempDir, String moduleTempResultsDir) {
        logger.log(Level.INFO, "Pasco results path: {0}", moduleTempResultsDir); //NON-NLS
        boolean foundHistory = false;

        final File pascoRoot = InstalledFileLocator.getDefault().locate("pasco2", ExtractIE.class.getPackage().getName(), false); //NON-NLS
        if (pascoRoot == null) {
            this.addErrorMessage(
                    NbBundle.getMessage(this.getClass(), "ExtractIE.getHistory.errMsg.unableToGetHist", this.getName()));
            logger.log(Level.SEVERE, "Error finding pasco program "); //NON-NLS
            return;
        }

        final String pascoHome = pascoRoot.getAbsolutePath();
        logger.log(Level.INFO, "Pasco2 home: {0}", pascoHome); //NON-NLS

        PASCO_LIB_PATH = pascoHome + File.separator + "pasco2.jar" + File.pathSeparator //NON-NLS
                + pascoHome + File.separator + "*";

        File resultsDir = new File(moduleTempResultsDir);
        resultsDir.mkdirs();

        // get index.dat files
        FileManager fileManager = currentCase.getServices().getFileManager();
        List<AbstractFile> indexFiles;
        try {
            indexFiles = fileManager.findFiles(dataSource, "index.dat"); //NON-NLS
        } catch (TskCoreException ex) {
            this.addErrorMessage(NbBundle.getMessage(this.getClass(), "ExtractIE.getHistory.errMsg.errGettingHistFiles",
                    this.getName()));
            logger.log(Level.WARNING, "Error fetching 'index.data' files for Internet Explorer history."); //NON-NLS
            return;
        }

        if (indexFiles.isEmpty()) {
            String msg = NbBundle.getMessage(this.getClass(), "ExtractIE.getHistory.errMsg.noHistFiles");
            logger.log(Level.INFO, msg);
            return;
        }

        dataFound = true;
        Collection<BlackboardArtifact> bbartifacts = new ArrayList<>();
        String temps;
        String indexFileName;
        for (AbstractFile indexFile : indexFiles) {
            // Since each result represent an index.dat file,
            // just create these files with the following notation:
            // index<Number>.dat (i.e. index0.dat, index1.dat,..., indexN.dat)
            // where <Number> is the obj_id of the file.
            // Write each index.dat file to a temp directory.
            //BlackboardArtifact bbart = fsc.newArtifact(ARTIFACT_TYPE.TSK_WEB_HISTORY);
            indexFileName = "index" + Integer.toString((int) indexFile.getId()) + ".dat"; //NON-NLS
            //indexFileName = "index" + Long.toString(bbart.getArtifactID()) + ".dat";
            temps = moduleTempDir + File.separator + indexFileName; //NON-NLS
            File datFile = new File(temps);
            if (context.dataSourceIngestIsCancelled()) {
                break;
            }
            try {
                ContentUtils.writeToFile(indexFile, datFile, context::dataSourceIngestIsCancelled);
            } catch (IOException e) {
                logger.log(Level.WARNING, "Error while trying to write index.dat file " + datFile.getAbsolutePath(), e); //NON-NLS
                this.addErrorMessage(
                        NbBundle.getMessage(this.getClass(), "ExtractIE.getHistory.errMsg.errWriteFile", this.getName(),
                                datFile.getAbsolutePath()));
                continue;
            }

            String filename = "pasco2Result." + indexFile.getId() + ".txt"; //NON-NLS
            boolean bPascProcSuccess = executePasco(temps, filename, moduleTempResultsDir);
            if (context.dataSourceIngestIsCancelled()) {
                return;
            }

            //At this point pasco2 proccessed the index files.
            //Now fetch the results, parse them and the delete the files.
            if (bPascProcSuccess) {
                // Don't add TSK_OS_ACCOUNT artifacts to the ModuleDataEvent
                bbartifacts.addAll(parsePascoOutput(indexFile, filename, moduleTempResultsDir).stream()
                        .filter(bbart -> bbart.getArtifactTypeID() == ARTIFACT_TYPE.TSK_WEB_HISTORY.getTypeID())
                        .collect(Collectors.toList()));
                if (context.dataSourceIngestIsCancelled()) {
                    return;
                }
                foundHistory = true;

                //Delete index<n>.dat file since it was succcessfully by Pasco
                datFile.delete();
            } else {
                logger.log(Level.WARNING, "pasco execution failed on: {0}", filename); //NON-NLS
                this.addErrorMessage(
                        NbBundle.getMessage(this.getClass(), "ExtractIE.getHistory.errMsg.errProcHist", this.getName()));
            }
        }

        if(!context.dataSourceIngestIsCancelled()) {
            postArtifacts(bbartifacts);
        }
    }

    /**
     * Execute pasco on a single file that has been saved to disk.
     *
     * @param indexFilePath  Path to local index.dat file to analyze
     * @param outputFileName Name of file to save output to
     * @param moduleTempResultsDir the path to the module temp directory.
     *
     * @return false on error
     */
    @Messages({
        "# {0} - sub module name", 
        "ExtractIE_executePasco_errMsg_errorRunningPasco={0}: Error analyzing Internet Explorer web history",
    })
    private boolean executePasco(String indexFilePath, String outputFileName, String moduleTempResultsDir) {
        boolean success = true;
        try {
            final String outputFileFullPath = moduleTempResultsDir + File.separator + outputFileName;
            final String errFileFullPath = moduleTempResultsDir + File.separator + outputFileName + ".err"; //NON-NLS
            logger.log(Level.INFO, "Writing pasco results to: {0}", outputFileFullPath); //NON-NLS   
            List<String> commandLine = new ArrayList<>();
            commandLine.add(JAVA_PATH);
            commandLine.add("-cp"); //NON-NLS
            commandLine.add(PASCO_LIB_PATH);
            commandLine.add("isi.pasco2.Main"); //NON-NLS
            commandLine.add("-T"); //NON-NLS
            commandLine.add("history");  //NON-NLS
            commandLine.add(indexFilePath);
            ProcessBuilder processBuilder = new ProcessBuilder(commandLine);
            processBuilder.redirectOutput(new File(outputFileFullPath));
            processBuilder.redirectError(new File(errFileFullPath));
            /*
             * NOTE on Pasco return codes: There is no documentation for Pasco.
             * Looking at the Pasco source code I see that when something goes
             * wrong Pasco returns a negative number as a return code. However,
             * we should still attempt to parse the Pasco output even if that
             * happens. I have seen many situations where Pasco output file
             * contains a lot of useful data and only the last entry is
             * corrupted.
             */
            ExecUtil.execute(processBuilder, new DataSourceIngestModuleProcessTerminator(context, true));
            // @@@ Investigate use of history versus cache as type.
        } catch (IOException ex) {
            logger.log(Level.SEVERE, "Error executing Pasco to process Internet Explorer web history", ex); //NON-NLS
            addErrorMessage(Bundle.ExtractIE_executePasco_errMsg_errorRunningPasco(getName()));            
            success = false;
        }
        return success;
    }

    /**
     * parse Pasco output and create artifacts
     *
     * @param origFile            Original index.dat file that was analyzed to
     *                            get this output
     * @param pascoOutputFileName name of pasco output file
     * @param moduleTempResultsDir the path to the module temp directory.
     *
     * @return A collection of created artifacts
     */
    private Collection<BlackboardArtifact> parsePascoOutput(AbstractFile origFile, String pascoOutputFileName, String moduleTempResultsDir) {

        Collection<BlackboardArtifact> bbartifacts = new ArrayList<>();
        String fnAbs = moduleTempResultsDir + File.separator + pascoOutputFileName;

        File file = new File(fnAbs);
        if (file.exists() == false) {
            this.addErrorMessage(
                    NbBundle.getMessage(this.getClass(), "ExtractIE.parsePascoOutput.errMsg.notFound", this.getName(),
                            file.getName()));
            logger.log(Level.WARNING, "Pasco Output not found: {0}", file.getPath()); //NON-NLS
            return bbartifacts;
        }

        // Make sure the file the is not empty or the Scanner will
        // throw a "No Line found" Exception
        if (file.length() == 0) {
            return bbartifacts;
        }

        Scanner fileScanner;
        try {
            fileScanner = new Scanner(new FileInputStream(file.toString()));
        } catch (FileNotFoundException ex) {
            this.addErrorMessage(
                    NbBundle.getMessage(this.getClass(), "ExtractIE.parsePascoOutput.errMsg.errParsing", this.getName(),
                            file.getName()));
            logger.log(Level.WARNING, "Unable to find the Pasco file at " + file.getPath(), ex); //NON-NLS
            return bbartifacts;
        }
        while (fileScanner.hasNext()) {

            if (context.dataSourceIngestIsCancelled()) {
                return bbartifacts;
            }

            String line = fileScanner.nextLine();
            if (!line.startsWith("URL")) {   //NON-NLS
                continue;
            }

            String[] lineBuff = line.split("\\t"); //NON-NLS

            if (lineBuff.length < 4) {
                logger.log(Level.INFO, "Found unrecognized IE history format."); //NON-NLS
                continue;
            }

            String actime = lineBuff[3];
            Long ftime = (long) 0;
            String user = "";
            String realurl = null;
            String domain;

            /*
             * We've seen two types of lines: URL http://XYZ.com .... URL
             * Visited: Joe@http://XYZ.com ....
             */
            if (lineBuff[1].contains("@")) {
                String url[] = lineBuff[1].split("@", 2);

                /*
                 * Verify the left portion of the URL is valid.
                 */
                domain = extractDomain(url[0]);

                if (domain != null && domain.isEmpty() == false) {
                    /*
                     * Use the entire input for the URL.
                     */
                    realurl = lineBuff[1].trim();
                } else {
                    /*
                     * Use the left portion of the input for the user, and the
                     * right portion for the host.
                     */
                    user = url[0];
                    user = user.replace("Visited:", ""); //NON-NLS
                    user = user.replace(":Host:", ""); //NON-NLS
                    user = user.replaceAll("(:)(.*?)(:)", "");
                    user = user.trim();
                    realurl = url[1];
                    realurl = realurl.replace("Visited:", ""); //NON-NLS
                    realurl = realurl.replaceAll(":(.*?):", "");
                    realurl = realurl.replace(":Host:", ""); //NON-NLS
                    realurl = realurl.trim();
                    domain = extractDomain(realurl);
                }
            } else {
                /*
                 * Use the entire input for the URL.
                 */
                realurl = lineBuff[1].trim();
                domain = extractDomain(realurl);
            }

            if (!actime.isEmpty()) {
                try {
                    Long epochtime = dateFormatter.parse(actime).getTime();
                    ftime = epochtime / 1000;
                } catch (ParseException e) {
                    this.addErrorMessage(
                            NbBundle.getMessage(this.getClass(), "ExtractIE.parsePascoOutput.errMsg.errParsingEntry",
                                    this.getName()));
                    logger.log(Level.WARNING, String.format("Error parsing Pasco results, may have partial processing of corrupt file (id=%d)", origFile.getId()), e); //NON-NLS
                }
            }

            Collection<BlackboardAttribute> bbattributes = new ArrayList<>();
            bbattributes.add(new BlackboardAttribute(ATTRIBUTE_TYPE.TSK_URL,
                    RecentActivityExtracterModuleFactory.getModuleName(), realurl));
            //bbattributes.add(new BlackboardAttribute(ATTRIBUTE_TYPE.TSK_URL_DECODED.getTypeID(), "RecentActivity", EscapeUtil.decodeURL(realurl)));

            bbattributes.add(new BlackboardAttribute(ATTRIBUTE_TYPE.TSK_DATETIME_ACCESSED,
                    RecentActivityExtracterModuleFactory.getModuleName(), ftime));
            bbattributes.add(new BlackboardAttribute(ATTRIBUTE_TYPE.TSK_REFERRER,
                    RecentActivityExtracterModuleFactory.getModuleName(), ""));
            // @@@ NOte that other browser modules are adding TITLE in here for the title
            bbattributes.add(new BlackboardAttribute(ATTRIBUTE_TYPE.TSK_PROG_NAME,
                    RecentActivityExtracterModuleFactory.getModuleName(),
                    NbBundle.getMessage(this.getClass(),
                            "ExtractIE.moduleName.text")));
            if (domain != null && domain.isEmpty() == false) {
                bbattributes.add(new BlackboardAttribute(ATTRIBUTE_TYPE.TSK_DOMAIN,
                        RecentActivityExtracterModuleFactory.getModuleName(), domain));
            }
            bbattributes.add(new BlackboardAttribute(ATTRIBUTE_TYPE.TSK_USER_NAME,
                    RecentActivityExtracterModuleFactory.getModuleName(), user));

            try {
                bbartifacts.add(createArtifactWithAttributes(TSK_WEB_HISTORY, origFile, bbattributes));
            } catch (TskCoreException ex) {
                logger.log(Level.SEVERE, String.format("Failed to create %s for file %d",ARTIFACT_TYPE.TSK_WEB_HISTORY.getDisplayName(), origFile.getId() ), ex);
            }
        }
        fileScanner.close();
        return bbartifacts;
    }

    /**
     * Extract the domain from the supplied URL. This method does additional
     * checks to detect invalid URLs.
     *
     * @param url The URL from which to extract the domain.
     *
     * @return The domain.
     */
    private String extractDomain(String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }

        if (url.toLowerCase().startsWith(RESOURCE_URL_PREFIX)) {
            /*
             * Ignore URLs that begin with the matched text.
             */
            return null;
        }

        return NetworkUtils.extractDomain(url);
    }
}
