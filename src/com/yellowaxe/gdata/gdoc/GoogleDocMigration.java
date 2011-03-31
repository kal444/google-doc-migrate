package com.yellowaxe.gdata.gdoc;

import static java.lang.String.format;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;

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
import com.google.gdata.data.docs.DocumentListEntry;
import com.google.gdata.data.docs.DocumentListFeed;
import com.google.gdata.data.media.MediaSource;
import com.google.gdata.util.AuthenticationException;
import com.google.gdata.util.ServiceException;

public class GoogleDocMigration {

    private static final Logger LOG = LoggerFactory.getLogger(GoogleDocMigration.class);

    private static final String DOC_FEED_ROOT = "https://docs.google.com/feeds/default/private/full/";

    private static final String DOCS_OWNED_BY_ME = DOC_FEED_ROOT + "-/mine";

    private static final String DOCS_SHARED_WITH_ME = DOC_FEED_ROOT + "-/-mine";

    private DocsService origDocService;

    private DocsService desgDocService;

    private SpreadsheetService origSpreadsheetService;

    private SpreadsheetService destSpreadsheetService;

    public void migrateMyDocuments() {
        LOG.info("Migrating Documents Owned By Me");

        try {
            URL feedUri = new URL(DOCS_OWNED_BY_ME);
            DocumentListFeed feed = origDocService.getFeed(feedUri, DocumentListFeed.class);
            LOG.info(format("Found %d documents to migrate", feed.getEntries().size()));

            for (DocumentListEntry entry : feed.getEntries()) {
                LOG.info(format("title: %s (%s)", entry.getTitle().getPlainText(), entry.getResourceId()));

                DocumentListEntry newEntry = copyEntry(entry);

                newEntry.setTitle(new PlainTextConstruct(entry.getTitle().getPlainText()));
                newEntry.setStarred(entry.isStarred());
                newEntry.setHidden(entry.isHidden());
                newEntry.setWritersCanInvite(entry.isWritersCanInvite());

                for (Link parentLink : entry.getParentLinks()) {
                    System.out.println(parentLink.getTitle());
                    System.out.println(parentLink.getRel());
                    System.out.println(parentLink.getType());
                    System.out.println("oldhref " + parentLink.getHref());

                    String newHref = findExactMatchHrefForFolder(parentLink);
                    System.out.println("found   " + newHref);
                    newEntry.addLink(DocumentListEntry.PARENT_NAMESPACE,
                                     "application/atom+xml", newHref);
                }

                AclFeed aclFeed = origDocService.getFeed(new URL(entry.getAclFeedLink().getHref()), AclFeed.class);
                for (AclEntry aclEntry : aclFeed.getEntries()) {
                    System.out.println(format(" - scope: %s(%s) - role: %s", aclEntry.getScope().getValue(),
                                              aclEntry.getScope().getType(), aclEntry.getRole().getValue()));
                }

                newEntry = newEntry.update();
                for (Link parentLink : newEntry.getParentLinks()) {
                    System.out.println(parentLink.getTitle());
                    System.out.println(parentLink.getRel());
                    System.out.println(parentLink.getType());
                    System.out.println("newhref " + parentLink.getHref());
                }

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

    private String findExactMatchHrefForFolder(Link parentLink) throws MalformedURLException, IOException,
        ServiceException {
        URL searchFeedUri = new URL(DOC_FEED_ROOT + "-/folder");
        DocumentQuery query = new DocumentQuery(searchFeedUri);
        query.setTitleQuery(parentLink.getTitle());
        query.setTitleExact(true);
        query.setMaxResults(10);
        DocumentListFeed searchFeed = origDocService.getFeed(query, DocumentListFeed.class);
        if (searchFeed.getEntries().size() == 1)
            return searchFeed.getEntries().get(0).getSelfLink().getHref();
        return null;
    }

    private DocumentListEntry copyEntry(DocumentListEntry entry) throws MalformedURLException, IOException,
        ServiceException {
        String entryType = entry.getType();
        if (entryType.equals("spreadsheet"))
            // spreadsheet
            return copySpreadsheet(entry.getResourceId());
        else if (entryType.equals("document"))
            // doc
            return null;
        else if (entryType.equals("presentation"))
            // presentation
            return null;
        else if (entryType.equals("pdf"))
            return null;
        else
            return null;
    }

    private DocumentListEntry copySpreadsheet(String resourceId) throws IOException, MalformedURLException,
        ServiceException {
        String docId = resourceId.substring(resourceId.lastIndexOf(":") + 1);
        String exportUrl =
            "https://spreadsheets.google.com/feeds/download/spreadsheets" + "/Export?key=" + docId
                + "&exportFormat=xls";

        UserToken docsToken = (UserToken) origDocService.getAuthTokenFactory().getAuthToken();
        UserToken spreadsheetsToken = (UserToken) origSpreadsheetService.getAuthTokenFactory().getAuthToken();
        origDocService.setUserToken(spreadsheetsToken.getValue());

        String filepath = System.getProperty("java.io.tmpdir") + "/" + "temp.xls";
        downloadFile(exportUrl, filepath);

        // Restore docs token for our DocList client
        origDocService.setUserToken(docsToken.getValue());

        return uploadFile(filepath, "TemporaryTitle", new URL(DOC_FEED_ROOT));
    }

    public DocumentListEntry uploadFile(String filepath, String title, URL uri)
        throws IOException, ServiceException {
        File file = new File(filepath);
        DocumentListEntry newDocument = new DocumentListEntry();
        String mimeType = DocumentListEntry.MediaType.fromFileName(file.getName()).getMimeType();
        newDocument.setFile(file, mimeType);
        newDocument.setTitle(new PlainTextConstruct(title));

        DocumentListEntry newEntry = origDocService.insert(uri, newDocument);
        if (newEntry != null) {
            file.delete();
        }

        return newEntry;
    }

    private void downloadFile(String exportUrl, String filepath)
        throws IOException, MalformedURLException, ServiceException {
        System.out.println("Exporting document from: " + exportUrl);

        MediaContent mc = new MediaContent();
        mc.setUri(exportUrl);
        MediaSource ms = origDocService.getMedia(mc);

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

    public GoogleDocMigration(String origUsername, String origPassword, String destUsername, String destPassword) {
        origDocService = new DocsService("yellowaxe.com-GoogleDocMigration-v1");
        origSpreadsheetService = new SpreadsheetService("yellowaxe.com-GoogleDocMigration-v1");

        desgDocService = new DocsService("yellowaxe.com-GoogleDocMigration-v1");
        destSpreadsheetService = new SpreadsheetService("yellowaxe.com-GoogleDocMigration-v1");

        try {
            origDocService.setUserCredentials(origUsername, origPassword);
            origSpreadsheetService.setUserCredentials(origUsername, origPassword);

            desgDocService.setUserCredentials(destUsername, destPassword);
            destSpreadsheetService.setUserCredentials(destUsername, destPassword);
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
            new GoogleDocMigration(commandArgs.origUsername, commandArgs.origPassword, commandArgs.destUsername,
                                   commandArgs.destPassword);

        migration.migrateMyDocuments();
    }

}
