package com.yellowaxe.gdata.gdoc;

import static java.lang.String.format;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

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

    private DocsServiceFacade origDocsServiceFacade;

    private DocsServiceFacade destDocsServiceFacade;

    private boolean testOnly;

    private List<DocumentListEntry> failedEntries = new ArrayList<DocumentListEntry>();

    public void migrateMyDocuments() {

        LOG.info("Migrating Documents Owned By Me");

        try {
            URL feedUri = new URL(DOCS_OWNED_BY_ME);
            DocumentListFeed feed = origDocsService.getFeed(feedUri, DocumentListFeed.class);
            logEntries(feed);

            for (DocumentListEntry entry : feed.getEntries()) {
                try {
                    logEntry(entry);

                    Set<String> folders = gatherAllFolders(entry);
                    if (folders.contains(MIGRATION_TAG_FOLDER_NAME)) {
                        LOG.info("already migrated, skipping...");
                        continue;
                    }

                    Set<AclHolder> aclHolders = gatherAllAcls(entry);

                    DocumentListEntry newEntry = copyEntry(entry);
                    newEntry = copyMetadata(entry, newEntry);
                    newEntry = synchronizeFolders(folders, newEntry);
                    synchronizeAcls(aclHolders, newEntry);
                    markMigrated(entry);

                    LOG.info("====");
                } catch (Exception e) {
                    // continue to next entry
                    failedEntries.add(entry);
                    e.printStackTrace();
                    continue;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        } catch (ServiceException e) {
            e.printStackTrace();
        }
    }

    /**
     * DOES NOT WORK - adding permission to the ACL if you aren't the owner
     * doesn't seem to work. Even if the writerCanInvite bit is set.
     */
    public void migrateDocumentsSharedWithMe() {

        LOG.info("Migrating Documents Shared With Me");

        try {
            URL feedUri = new URL(DOCS_SHARED_WITH_ME);
            DocumentListFeed feed = origDocsService.getFeed(feedUri, DocumentListFeed.class);
            logEntries(feed);

            for (DocumentListEntry entry : feed.getEntries()) {
                try {
                    logEntry(entry);

                    Set<String> folders = gatherAllFolders(entry);
                    if (folders.contains(MIGRATION_TAG_FOLDER_NAME)) {
                        LOG.info("already migrated, skipping...");
                        continue;
                    }

                    AclFeed aclFeed =
                        origDocsService.getFeed(new URL(entry.getAclFeedLink().getHref()),
                                                AclFeed.class);
                    AclHolder holder = findSharingAclFor(origUsername, aclFeed);
                    if (holder == null) {
                        // something isn't right
                        LOG.error("Unable to find your permission");
                        failedEntries.add(entry);
                        continue;
                    }
                    updateSharingAclTo(destUsername, entry, holder);

                    // synchronizing folders for shared Doc
                    DocumentListEntry newEntry =
                        destDocsServiceFacade.findEntryByName(entry.getTitle().getPlainText());
                    if (newEntry != null) {
                        newEntry = synchronizeFolders(folders, newEntry);
                    }

                    markMigrated(entry);

                    LOG.info("====");
                } catch (Exception e) {
                    // continue to next entry
                    failedEntries.add(entry);
                    e.printStackTrace();
                    continue;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        } catch (ServiceException e) {
            e.printStackTrace();
        }
    }

    private void updateSharingAclTo(String username, DocumentListEntry entry, AclHolder holder)
        throws IOException, MalformedURLException, ServiceException {

        if (entry.isWritersCanInvite() && holder != null
            && holder.getRole().equals(AclRole.WRITER.getValue())) {
            AclHolder newHolder =
                new AclHolder(AclScope.Type.USER, AclRole.WRITER.getValue(), username);

            LOG.info("adding sharing for " + newHolder);
            addAcl(entry, newHolder);
        } else {
            LOG.warn(format(
                            "Cannot change ACL for this entry [title: %s]. WritersCanInviteFlag is off or You aren't a writer",
                            entry.getTitle().getPlainText()));
            failedEntries.add(entry);
        }
    }

    private AclHolder findSharingAclFor(String username, AclFeed aclFeed) {

        AclHolder holder = null;
        for (AclEntry aclEntry : aclFeed.getEntries()) {
            if (aclEntry.getScope().getValue().equalsIgnoreCase(username)) {
                // found the ACL we want to save
                holder =
                    new AclHolder(aclEntry.getScope().getType(), aclEntry.getRole().getValue(),
                                  aclEntry.getScope().getValue());
                LOG.info(holder.toString());
                break;
            }
        }
        return holder;
    }

    private void synchronizeAcls(Set<AclHolder> aclHolders, DocumentListEntry newEntry)
        throws IOException, MalformedURLException, ServiceException {

        for (AclHolder holder : aclHolders) {
            LOG.info("adding sharing for " + holder);
            addAcl(newEntry, holder);
        }
    }

    private DocumentListEntry synchronizeFolders(Set<String> folders, DocumentListEntry entry)
        throws IOException, MalformedURLException, ServiceException {

        DocumentListEntry newEntry = entry;

        for (String folderName : folders) {
            LOG.debug("adding to folder: " + folderName);
            if (isNotATest()) {
                newEntry = addToFolder(entry, folderName);
            }
        }
        return newEntry;
    }

    private Set<AclHolder> gatherAllAcls(DocumentListEntry entry) throws IOException,
        ServiceException, MalformedURLException {

        Set<AclHolder> aclHolders = new HashSet<AclHolder>();
        AclFeed aclFeed =
            origDocsService.getFeed(new URL(entry.getAclFeedLink().getHref()), AclFeed.class);
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
        return aclHolders;
    }

    private Set<String> gatherAllFolders(DocumentListEntry entry) {

        Set<String> folders = new TreeSet<String>();
        for (Link parentLink : entry.getParentLinks()) {
            // saving the folders the doc is in
            folders.add(parentLink.getTitle());
        }
        if (!folders.isEmpty()) {
            LOG.info("folders: " + folders);
        }
        return folders;
    }

    private void logEntries(DocumentListFeed feed) {

        LOG.info(format("Found %d documents to migrate", feed.getEntries().size()));
    }

    private void logEntry(DocumentListEntry entry) {

        LOG.info(format("title: %s (%s)", entry.getTitle().getPlainText(), entry.getResourceId()));
    }

    private DocumentListEntry markMigrated(DocumentListEntry entry) throws MalformedURLException,
        IOException, ServiceException {

        DocumentListEntry newEntry = entry;

        if (isNotATest()) {
            DocsServiceFacade service = origDocsServiceFacade;
            newEntry =
                service.addToFolder(entry, service.findOrCreateFolder(MIGRATION_TAG_FOLDER_NAME));
        }
        return newEntry;
    }

    private DocumentListEntry copyMetadata(DocumentListEntry entry, DocumentListEntry newEntry)
        throws IOException, ServiceException {

        if (isNotATest()) {
            newEntry.setTitle(new PlainTextConstruct(entry.getTitle().getPlainText()));
            newEntry.setStarred(entry.isStarred());
            newEntry.setHidden(entry.isHidden());
            newEntry.setWritersCanInvite(entry.isWritersCanInvite());
            return newEntry.update();
        }

        return entry;
    }

    private DocumentListEntry copyEntry(DocumentListEntry entry) throws IOException,
        ServiceException, MalformedURLException {

        DocumentListEntry newEntry = entry;

        if (isNotATest()) {
            newEntry =
                destDocsServiceFacade.uploadFile(origDocsServiceFacade.downloadEntry(entry),
                                                 TEMP_TITLE, rootUri());
        }
        return newEntry;
    }

    private URL rootUri() throws MalformedURLException {

        return new URL(DocsServiceFacade.DOC_FEED_ROOT);
    }

    private void addAcl(DocumentListEntry newEntry, AclHolder holder) throws IOException,
        MalformedURLException, ServiceException {

        if (isNotATest()) {
            destDocsServiceFacade.addAcl(new AclRole(holder.getRole()),
                                         new AclScope(holder.getType(), holder.getScope()),
                                         newEntry);
        }
    }

    private boolean isNotATest() {

        return !testOnly;
    }

    private DocumentListEntry addToFolder(DocumentListEntry newEntry, String folderName)
        throws IOException, MalformedURLException, ServiceException {

        DocsServiceFacade service = destDocsServiceFacade;
        return service.addToFolder(newEntry, service.findOrCreateFolder(folderName));
    }

    private void showFailed() {

        if (failedEntries.isEmpty())
            return;

        LOG.warn(format("The following %d documents were NOT migrated due to various errors",
                        failedEntries.size()));
        for (DocumentListEntry entry : failedEntries) {
            logEntry(entry);
        }
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
        DocsService destDocsService = new DocsService("yellowaxe.com-GoogleDocMigration-v1");

        try {
            origDocsService.setUserCredentials(origUsername, origPassword);
            destDocsService.setUserCredentials(destUsername, destPassword);

            // connect to spreadsheet service and save the token
            SpreadsheetService origSpreadsheetService =
                new SpreadsheetService("yellowaxe.com-GoogleDocMigration-v1");
            origSpreadsheetService.setUserCredentials(origUsername, origPassword);
            UserToken origSpreadsheetToken =
                (UserToken) origSpreadsheetService.getAuthTokenFactory().getAuthToken();

            // save the docs service token as well
            UserToken origDocsServiceToken =
                (UserToken) origDocsService.getAuthTokenFactory().getAuthToken();

            origDocsServiceFacade =
                new DocsServiceFacade(origDocsService, origDocsServiceToken, origSpreadsheetToken);
            destDocsServiceFacade = new DocsServiceFacade(destDocsService, null, null);

        } catch (AuthenticationException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {

        // Clear all JUL logging handlers
        java.util.logging.Logger rootLogger = LogManager.getLogManager().getLogger("");
        Handler[] handlers = rootLogger.getHandlers();
        for (Handler handler : handlers) {
            rootLogger.removeHandler(handler);
        }
        rootLogger.setLevel(Level.ALL);

        // install bridge since gdata uses JUL
        SLF4JBridgeHandler.install();

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
        migration.migrateDocumentsSharedWithMe();
        LOG.info("ALL DONE!");

        migration.showFailed();
    }

}
