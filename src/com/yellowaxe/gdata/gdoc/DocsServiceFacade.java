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

import com.google.gdata.client.DocumentQuery;
import com.google.gdata.client.GoogleAuthTokenFactory.UserToken;
import com.google.gdata.client.docs.DocsService;
import com.google.gdata.data.MediaContent;
import com.google.gdata.data.PlainTextConstruct;
import com.google.gdata.data.acl.AclEntry;
import com.google.gdata.data.acl.AclRole;
import com.google.gdata.data.acl.AclScope;
import com.google.gdata.data.docs.DocumentEntry;
import com.google.gdata.data.docs.DocumentListEntry;
import com.google.gdata.data.docs.DocumentListFeed;
import com.google.gdata.data.docs.FolderEntry;
import com.google.gdata.data.docs.PdfEntry;
import com.google.gdata.data.docs.PresentationEntry;
import com.google.gdata.data.docs.SpreadsheetEntry;
import com.google.gdata.data.media.MediaSource;
import com.google.gdata.util.ServiceException;

public class DocsServiceFacade {

    public static final String DOC_FEED_ROOT =
        "https://docs.google.com/feeds/default/private/full/";

    public static final String SPREADSHEET_EXPORT_URL_PATTERN =
        "https://spreadsheets.google.com/feeds/download/spreadsheets/Export?key=%s&exportFormat=xls";

    private static final Logger LOG = LoggerFactory.getLogger(DocsServiceFacade.class);

    private DocsService docsService;

    private UserToken docsServiceToken;

    private UserToken spreadsheetToken;

    public DocsServiceFacade(DocsService docsService, UserToken docsServiceToken,
                             UserToken spreadsheetToken) {

        super();
        this.docsService = docsService;
        this.docsServiceToken = docsServiceToken;
        this.spreadsheetToken = spreadsheetToken;
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

        return docsService.insert(new URL(destFolderUri), newEntry);
    }

    public DocumentListEntry findOrCreateFolder(String folderName) throws MalformedURLException,
        IOException, ServiceException {

        URL searchFeedUri = new URL(DOC_FEED_ROOT + "-/folder");
        DocumentQuery query = new DocumentQuery(searchFeedUri);
        query.setTitleQuery(folderName);
        query.setTitleExact(true);
        DocumentListFeed searchFeed = docsService.getFeed(query, DocumentListFeed.class);
        if (searchFeed.getEntries().size() == 1)
            return searchFeed.getEntries().get(0);
        else
            return createFolder(folderName);
    }

    private DocumentListEntry createFolder(String folderName) throws MalformedURLException,
        IOException, ServiceException {

        DocumentListEntry newEntry = new FolderEntry();
        newEntry.setTitle(new PlainTextConstruct(folderName));
        URL feedUrl = new URL(DOC_FEED_ROOT);

        LOG.info(format("Creating %s folder", folderName));
        return docsService.insert(feedUrl, newEntry);
    }

    public AclEntry addAcl(AclRole role, AclScope scope, DocumentListEntry entry)
        throws IOException, MalformedURLException, ServiceException {

        AclEntry aclEntry = new AclEntry();
        aclEntry.setRole(role);
        aclEntry.setScope(scope);

        return docsService.insert(new URL(entry.getAclFeedLink().getHref()), aclEntry);
    }

    public String downloadEntry(DocumentListEntry entry) throws MalformedURLException, IOException,
        ServiceException {

        String resourceId = entry.getResourceId();
        String docId = resourceId.substring(resourceId.lastIndexOf(":") + 1);
        String entryType = entry.getType();

        if (entryType.equals("spreadsheet"))
            return downloadSpreadsheet(docId);
        else if (entryType.equals("document"))
            return null;
        else if (entryType.equals("presentation"))
            return null;
        else if (entryType.equals("pdf"))
            return null;
        else
            return null;
    }

    private String downloadSpreadsheet(String docId) throws IOException, MalformedURLException,
        ServiceException {

        docsService.setUserToken(spreadsheetToken.getValue());

        String filepath = tempFile("temp.xls");
        downloadFile(format(SPREADSHEET_EXPORT_URL_PATTERN, docId), filepath);

        // Restore docs token for our Docs client
        docsService.setUserToken(docsServiceToken.getValue());

        return filepath;
    }

    private String tempFile(String filename) {

        String filepath = System.getProperty("java.io.tmpdir") + "/" + filename;
        return filepath;
    }

    private void downloadFile(String exportUrl, String filepath) throws IOException,
        MalformedURLException, ServiceException {

        MediaContent mc = new MediaContent();
        mc.setUri(exportUrl);
        MediaSource ms = docsService.getMedia(mc);

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

    public DocumentListEntry uploadFile(String filepath, String title, URL uri) throws IOException,
        ServiceException {

        File file = new File(filepath);
        DocumentListEntry newDocument = new DocumentListEntry();
        String mimeType = DocumentListEntry.MediaType.fromFileName(file.getName()).getMimeType();
        newDocument.setFile(file, mimeType);
        newDocument.setTitle(new PlainTextConstruct(title));

        DocumentListEntry newEntry = docsService.insert(uri, newDocument);
        if (newEntry != null) {
            file.delete();
        }

        return newEntry;
    }

}
