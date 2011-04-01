package com.yellowaxe.gdata.gdoc;

import static java.lang.String.format;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.ParameterException;
import com.google.gdata.client.GoogleAuthTokenFactory.UserToken;
import com.google.gdata.client.docs.DocsService;
import com.google.gdata.client.spreadsheet.SpreadsheetService;
import com.google.gdata.data.Link;
import com.google.gdata.data.PlainTextConstruct;
import com.google.gdata.data.acl.AclEntry;
import com.google.gdata.data.acl.AclFeed;
import com.google.gdata.data.acl.AclRole;
import com.google.gdata.data.acl.AclScope;
import com.google.gdata.data.docs.DocumentListEntry;
import com.google.gdata.data.docs.DocumentListFeed;
import com.google.gdata.util.AuthenticationException;
import com.google.gdata.util.ServiceException;

public class GoogleDocMigration {

    private static final Logger LOG = LoggerFactory.getLogger(GoogleDocMigration.class);

    private static final String DOCS_OWNED_BY_ME = DocsServiceFacade.DOC_FEED_ROOT + "-/mine";

    private static final String DOCS_SHARED_WITH_ME = DocsServiceFacade.DOC_FEED_ROOT + "-/-mine";

    private static final String MIGRATION_TAG_FOLDER_NAME = "GDM-MigratedTag";

    private static final String TEMP_TITLE = "Temporary Title";

    private String origUsername;

    private String destUsername;

    private DocsService origDocsService;

    private DocsService destDocsService;

    private UserToken origDocsServiceToken;

    private UserToken origSpreadsheetToken;

    private DocsServiceFacade origDocsServiceFacade;

    private DocsServiceFacade destDocsServiceFacade;

    private boolean testOnly;

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
                    // saving the folders the doc is in
                    folders.add(parentLink.getTitle());
                }
                if (!folders.isEmpty()) {
                    LOG.info("folders: " + folders);
                }

                Set<AclHolder> aclHolders = new HashSet<AclHolder>();
                AclFeed aclFeed =
                    origDocsService.getFeed(new URL(entry.getAclFeedLink().getHref()),
                                            AclFeed.class);
                for (AclEntry aclEntry : aclFeed.getEntries()) {
                    AclHolder holder =
                        new AclHolder(aclEntry.getScope().getType(), aclEntry.getRole().getValue(),
                                      aclEntry.getScope().getValue());

                    // skip current user and destination user's ACL entries
                    if (holder.getScope().equalsIgnoreCase(origUsername)
                        || holder.getScope().equalsIgnoreCase(destUsername)) {
                        continue;
                    }

                    // saving the ACL entry of this doc
                    aclHolders.add(holder);
                    LOG.info(holder.toString());
                }

                // start migrating the doc
                DocumentListEntry newEntry = null;
                if (!testOnly) {
                    newEntry = copyEntry(entry);
                    newEntry = copyMetadata(entry, newEntry);
                }

                // synchronize folders
                for (String folderName : folders) {
                    LOG.debug("adding to folder: " + folderName);
                    if (!testOnly) {
                        newEntry = addToFolder(newEntry, folderName);
                    }
                }

                // update ACL
                for (AclHolder holder : aclHolders) {
                    LOG.info("adding sharing for " + holder);
                    if (!testOnly) {
                        addAcl(newEntry, holder);
                    }
                }

                // mark doc as done with a tag folder
                if (!testOnly) {
                    markMigrated(entry);
                }

                LOG.info("====");
            }
        } catch (MalformedURLException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (ServiceException e) {
            e.printStackTrace();
        }
    }

    private DocumentListEntry markMigrated(DocumentListEntry entry) throws MalformedURLException,
        IOException, ServiceException {

        DocsServiceFacade service = origDocsServiceFacade;
        return service.addToFolder(entry, service.findOrCreateFolder(MIGRATION_TAG_FOLDER_NAME));
    }

    private DocumentListEntry copyMetadata(DocumentListEntry entry, DocumentListEntry newEntry)
        throws IOException, ServiceException {

        newEntry.setTitle(new PlainTextConstruct(entry.getTitle().getPlainText()));
        newEntry.setStarred(entry.isStarred());
        newEntry.setHidden(entry.isHidden());
        newEntry.setWritersCanInvite(entry.isWritersCanInvite());
        newEntry = newEntry.update();
        return newEntry;
    }

    private DocumentListEntry copyEntry(DocumentListEntry entry) throws IOException,
        ServiceException, MalformedURLException {

        DocumentListEntry newEntry =
            destDocsServiceFacade.uploadFile(origDocsServiceFacade.downloadEntry(entry),
                                             TEMP_TITLE, rootUri());
        return newEntry;
    }

    private URL rootUri() throws MalformedURLException {

        return new URL(DocsServiceFacade.DOC_FEED_ROOT);
    }

    private void addAcl(DocumentListEntry newEntry, AclHolder holder) throws IOException,
        MalformedURLException, ServiceException {

        destDocsServiceFacade.addAcl(new AclRole(holder.getRole()),
                                     new AclScope(holder.getType(), holder.getScope()), newEntry);
    }

    private DocumentListEntry addToFolder(DocumentListEntry newEntry, String folderName)
        throws IOException, MalformedURLException, ServiceException {

        DocsServiceFacade service = destDocsServiceFacade;
        return service.addToFolder(newEntry, service.findOrCreateFolder(folderName));
    }

    public GoogleDocMigration(String origUsername, String origPassword, String destUsername,
                              String destPassword, boolean testOnly) {

        this.testOnly = testOnly;

        if (testOnly) {
            LOG.warn("********** TEST MODE - NO ACTION IS DONE **********");
        }

        this.origUsername = origUsername;
        this.destUsername = destUsername;

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

            origDocsServiceFacade =
                new DocsServiceFacade(origDocsService, origDocsServiceToken, origSpreadsheetToken);
            destDocsServiceFacade = new DocsServiceFacade(destDocsService, null, null);

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
                                   commandArgs.destUsername, commandArgs.destPassword,
                                   commandArgs.testOnly);

        migration.migrateMyDocuments();
    }

}
