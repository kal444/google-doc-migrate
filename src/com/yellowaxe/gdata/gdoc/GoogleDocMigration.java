package com.yellowaxe.gdata.gdoc;

import static java.lang.String.format;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Set;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.ParameterException;
import com.google.gdata.client.DocumentQuery;
import com.google.gdata.client.GoogleAuthTokenFactory.UserToken;
import com.google.gdata.client.docs.DocsService;
import com.google.gdata.client.spreadsheet.SpreadsheetService;
import com.google.gdata.data.Link;
import com.google.gdata.data.MediaContent;
import com.google.gdata.data.PlainTextConstruct;
import com.google.gdata.data.acl.AclEntry;
import com.google.gdata.data.acl.AclFeed;
import com.google.gdata.data.docs.DocumentEntry;
import com.google.gdata.data.docs.DocumentListEntry;
import com.google.gdata.data.docs.DocumentListFeed;
import com.google.gdata.data.docs.FolderEntry;
import com.google.gdata.data.docs.PdfEntry;
import com.google.gdata.data.docs.PresentationEntry;
import com.google.gdata.data.docs.SpreadsheetEntry;
import com.google.gdata.data.media.MediaSource;
import com.google.gdata.util.AuthenticationException;
import com.google.gdata.util.ServiceException;

public class GoogleDocMigration {

    private static final String SPREADSHEET_EXPORT_URL_PATTERN =
        "https://spreadsheets.google.com/feeds/download/spreadsheets/Export?key=%s&exportFormat=xls";

    private static final Logger LOG = LoggerFactory.getLogger(GoogleDocMigration.class);

    private static final String MIGRATION_TAG_FOLDER_NAME = "GDM-MigratedTag";

    private static final String DOC_FEED_ROOT =
        "https://docs.google.com/feeds/default/private/full/";

    private static final String DOCS_OWNED_BY_ME = DOC_FEED_ROOT + "-/mine";

    private static final String DOCS_SHARED_WITH_ME = DOC_FEED_ROOT + "-/-mine";

    private DocsService origDocsService;

    private DocsService destDocsService;

    private UserToken origDocsServiceToken;

    private UserToken origSpreadsheetToken;

    public void migrateMyDocuments() {

        LOG.info("Migrating Documents Owned By Me");

        try {
            URL feedUri = new URL(DOCS_OWNED_BY_ME);
            DocumentListFeed feed = origDocsService.getFeed(feedUri, DocumentListFeed.class);
            LOG.info(format("Found %d documents to migrate", feed.getEntries().size()));

            for (DocumentListEntry entry : feed.getEntries()) {
                LOG.info(format("title: %s (%s)", entry.getTitle().getPlainText(),
                                entry.getResourceId()));

                Set<String> folders = new TreeSet<String>();
                for (Link parentLink : entry.getParentLinks()) {
                    folders.add(parentLink.getTitle());
                }
                if (!folders.isEmpty()) {
                    LOG.info("folders: " + folders);
                }

                AclFeed aclFeed =
                    origDocsService.getFeed(new URL(entry.getAclFeedLink().getHref()),
                                            AclFeed.class);
                for (AclEntry aclEntry : aclFeed.getEntries()) {
                    LOG.info(format(" -acl role: %s -scope: %s(%s)", aclEntry.getRole().getValue(),
                                    aclEntry.getScope().getValue(), aclEntry.getScope().getType()));
                }

                // migrate doc
                DocumentListEntry newEntry = copyEntry(entry);

                // update metadata
                newEntry.setTitle(new PlainTextConstruct(entry.getTitle().getPlainText()));
                newEntry.setStarred(entry.isStarred());
                newEntry.setHidden(entry.isHidden());
                newEntry.setWritersCanInvite(entry.isWritersCanInvite());
                newEntry = newEntry.update();

                // synchronize folders
                for (String folderName : folders) {
                    LOG.debug("adding to folder: " + folderName);
                    newEntry = addToFolder(newEntry, findExactEntryForFolder(folderName));
                }

                // update ACL

                // mark doc as done with a tag folder
                newEntry = addToFolder(newEntry, createMigrationTagFolderIfNeeded());

                break;
            }
        } catch (MalformedURLException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (ServiceException e) {
            e.printStackTrace();
        }
    }

    private DocumentListEntry createMigrationTagFolderIfNeeded() throws IOException,
        ServiceException {

        DocumentListEntry tagFolder = findExactEntryForFolder(MIGRATION_TAG_FOLDER_NAME);
        if (tagFolder != null)
            return tagFolder;

        DocumentListEntry newEntry = new FolderEntry();
        newEntry.setTitle(new PlainTextConstruct(MIGRATION_TAG_FOLDER_NAME));
        URL feedUrl = new URL(DOC_FEED_ROOT);

        LOG.info("Creating migration tag folder");
        return origDocsService.insert(feedUrl, newEntry);
    }

    private DocumentListEntry findExactEntryForFolder(String folderName)
        throws MalformedURLException, IOException, ServiceException {

        URL searchFeedUri = new URL(DOC_FEED_ROOT + "-/folder");
        DocumentQuery query = new DocumentQuery(searchFeedUri);
        query.setTitleQuery(folderName);
        query.setTitleExact(true);
        DocumentListFeed searchFeed = origDocsService.getFeed(query, DocumentListFeed.class);
        if (searchFeed.getEntries().size() == 1)
            return searchFeed.getEntries().get(0);
        return null;
    }

    public DocumentListEntry addToFolder(DocumentListEntry sourceEntry,
                                         DocumentListEntry destFolderEntry) throws IOException,
        MalformedURLException, ServiceException {

        DocumentListEntry newEntry = null;

        String docType = sourceEntry.getType();
        if (docType.equals("spreadsheet")) {
            newEntry = new SpreadsheetEntry();
        } else if (docType.equals("document")) {
            newEntry = new DocumentEntry();
        } else if (docType.equals("presentation")) {
            newEntry = new PresentationEntry();
        } else if (docType.equals("pdf")) {
            newEntry = new PdfEntry();
        } else if (docType.equals("folder")) {
            newEntry = new FolderEntry();
        } else {
            newEntry = new DocumentListEntry(); // Unknown type
        }
        newEntry.setId(sourceEntry.getId());

        String destFolderUri = ((MediaContent) destFolderEntry.getContent()).getUri();

        return origDocsService.insert(new URL(destFolderUri), newEntry);
    }

    private DocumentListEntry copyEntry(DocumentListEntry entry) throws MalformedURLException,
        IOException, ServiceException {

        String entryType = entry.getType();
        if (entryType.equals("spreadsheet"))
            return copySpreadsheet(entry.getResourceId());
        else if (entryType.equals("document"))
            return null;
        else if (entryType.equals("presentation"))
            return null;
        else if (entryType.equals("pdf"))
            return null;
        else
            return null;
    }

    private DocumentListEntry copySpreadsheet(String resourceId) throws IOException,
        MalformedURLException, ServiceException {

        String docId = resourceId.substring(resourceId.lastIndexOf(":") + 1);
        origDocsService.setUserToken(origSpreadsheetToken.getValue());

        String filepath = tempFile("temp.xls");
        downloadFile(format(SPREADSHEET_EXPORT_URL_PATTERN, docId), filepath);

        // Restore docs token for our Docs client
        origDocsService.setUserToken(origDocsServiceToken.getValue());

        return uploadFile(filepath, "Temporary Title", new URL(DOC_FEED_ROOT));
    }

    private String tempFile(String filename) {

        String filepath = System.getProperty("java.io.tmpdir") + "/" + filename;
        return filepath;
    }

    public DocumentListEntry uploadFile(String filepath, String title, URL uri) throws IOException,
        ServiceException {

        File file = new File(filepath);
        DocumentListEntry newDocument = new DocumentListEntry();
        String mimeType = DocumentListEntry.MediaType.fromFileName(file.getName()).getMimeType();
        newDocument.setFile(file, mimeType);
        newDocument.setTitle(new PlainTextConstruct(title));

        DocumentListEntry newEntry = origDocsService.insert(uri, newDocument);
        if (newEntry != null) {
            file.delete();
        }

        return newEntry;
    }

    private void downloadFile(String exportUrl, String filepath) throws IOException,
        MalformedURLException, ServiceException {

        MediaContent mc = new MediaContent();
        mc.setUri(exportUrl);
        MediaSource ms = origDocsService.getMedia(mc);

        InputStream inStream = null;
        FileOutputStream outStream = null;

        try {
            inStream = ms.getInputStream();
            outStream = new FileOutputStream(filepath);

            int c;
            while ((c = inStream.read()) != -1) {
                outStream.write(c);
            }
        } finally {
            if (inStream != null) {
                inStream.close();
            }
            if (outStream != null) {
                outStream.flush();
                outStream.close();
            }
        }
    }

    public GoogleDocMigration(String origUsername, String origPassword, String destUsername,
                              String destPassword) {

        origDocsService = new DocsService("yellowaxe.com-GoogleDocMigration-v1");
        destDocsService = new DocsService("yellowaxe.com-GoogleDocMigration-v1");

        try {
            origDocsService.setUserCredentials(origUsername, origPassword);
            destDocsService.setUserCredentials(destUsername, destPassword);

            // connect to spreadsheet service and save the token
            SpreadsheetService origSpreadsheetService =
                new SpreadsheetService("yellowaxe.com-GoogleDocMigration-v1");
            origSpreadsheetService.setUserCredentials(origUsername, origPassword);
            origSpreadsheetToken =
                (UserToken) origSpreadsheetService.getAuthTokenFactory().getAuthToken();

            // save the docs service token as well
            origDocsServiceToken = (UserToken) origDocsService.getAuthTokenFactory().getAuthToken();

        } catch (AuthenticationException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {

        CommandArgs commandArgs = new CommandArgs();
        JCommander optParser = new JCommander(commandArgs);

        try {
            optParser.parse(args);
        } catch (ParameterException e) {
            optParser.usage();
            System.exit(1);
        }

        GoogleDocMigration migration =
            new GoogleDocMigration(commandArgs.origUsername, commandArgs.origPassword,
                                   commandArgs.destUsername, commandArgs.destPassword);

        migration.migrateMyDocuments();
    }

}
