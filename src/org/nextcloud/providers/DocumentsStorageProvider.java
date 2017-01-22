/**
 *   nextCloud Android client application
 *
 *   @author Bartosz Przybylski
 *   Copyright (C) 2016  Bartosz Przybylski <bart.p.pl@gmail.com>
 *
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License version 2,
 *   as published by the Free Software Foundation.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package org.nextcloud.providers;

import android.accounts.Account;
import android.annotation.TargetApi;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Point;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import android.support.annotation.Nullable;
import android.util.Log;

import com.owncloud.android.authentication.AccountUtils;
import com.owncloud.android.datamodel.FileDataStorageManager;
import com.owncloud.android.datamodel.OCFile;
import com.owncloud.android.files.services.FileDownloader;
import com.owncloud.android.files.services.FileUploader;

import org.nextcloud.providers.cursors.FileCursor;
import org.nextcloud.providers.cursors.RootCursor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Vector;

@TargetApi(Build.VERSION_CODES.KITKAT)
public class DocumentsStorageProvider extends DocumentsProvider {

    private static final String TAG = "Nextcloud DocProvider";
    private FileDataStorageManager mCurrentStorageManager = null;
    private static Map<Long, FileDataStorageManager> mRootIdToStorageManager;

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        Cursor childDocuments = null;
        try {
            childDocuments = queryChildDocuments(parentDocumentId, null, null);
        } catch (FileNotFoundException pE) {
            pE.printStackTrace();
        }
        if (childDocuments == null) return false;
        int columnId = childDocuments.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
        boolean notFound = true;
        while (notFound) {
            if (childDocuments.getString(columnId).equals(documentId)) notFound = false;
            if (!childDocuments.moveToNext()) break;
        }
        return !notFound;
    }

    @Override
    public Cursor queryRoots(String[] projection) throws FileNotFoundException {
        initiateStorageMap();

        final RootCursor result = new RootCursor(projection);

        for (Account account : AccountUtils.getAccounts(getContext())) {
            result.addRoot(account, getContext());
        }

        return result;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        final long docId = Long.parseLong(documentId);
        updateStorageManagerFromRootId(docId);

        final FileCursor result = new FileCursor(projection);
        OCFile file = mCurrentStorageManager.getFileById(docId);
        if (file != null) {
            result.addFile(file);
        }

        return result;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {

        final long folderId = Long.parseLong(parentDocumentId);
        initiateStorageMap();
        updateStorageManagerFromDocId(folderId);

        final FileCursor result = new FileCursor(projection);

        final OCFile browsedDir = mCurrentStorageManager.getFileById(folderId);
        for (OCFile file : mCurrentStorageManager.getFolderContent(browsedDir, false)) {
            result.addFile(file);
        }

        return result;
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal cancellationSignal)
            throws FileNotFoundException {
        final long docId = Long.parseLong(documentId);
        initiateStorageMap();
        updateStorageManagerFromDocId(docId);

        OCFile file = mCurrentStorageManager.getFileById(docId);

        if (!file.isDown()) {
            if (!downloadSuccessful(docId, cancellationSignal)) {
                return null;
            }
        }

        try {
            final OCFile finalFile = file;
            return ParcelFileDescriptor.open(
                    new File(file.getStoragePath()), ParcelFileDescriptor.MODE_READ_WRITE,
                    new Handler(),
                    new ParcelFileDescriptor.OnCloseListener() {
                        @Override
                        public void onClose(IOException e) {
                            new FileUploader.UploadRequester().uploadUpdate(getContext(),
                                    mCurrentStorageManager.getAccount(),
                                    finalFile,
                                    FileUploader.LOCAL_BEHAVIOUR_DELETE,
                                    true);
                        }
                    });
        } catch (IOException e) {
            e.printStackTrace();
            //If read/write fails, return read only.
            return ParcelFileDescriptor.open(new File(file.getStoragePath()), ParcelFileDescriptor.MODE_READ_ONLY);
        }
    }

    @Override
    public String createDocument(String parentDocumentId,
                                 String mimeType,
                                 String displayName) {
        Log.i(TAG, "try to create a file of the mime type: " + mimeType);
        final long parentId = Long.parseLong(parentDocumentId);
        initiateStorageMap();
        updateStorageManagerFromDocId(parentId);
        OCFile parent = mCurrentStorageManager.getFileById(parentId);
        if (!parent.isDown()) {
            if (!downloadSuccessful(parentId, null)) {
                return null;
            }
        }
        File file = new File(parent.getStoragePath().concat(File.pathSeparator).concat(displayName));
        try {
            file.createNewFile();
        } catch (IOException e) {
            e.printStackTrace();
        }
        new FileUploader.UploadRequester().uploadNewFile(getContext(),
                mCurrentStorageManager.getAccount(),
                file.getPath(),
                parent.getRemotePath().concat(OCFile.PATH_SEPARATOR).concat(displayName),
                FileUploader.LOCAL_BEHAVIOUR_DELETE,
                mimeType,
                true,
                0); // TODO: 1/20/17 Set createdBy correctly
        return "" + mCurrentStorageManager.getFileByLocalPath(file.getPath()).getFileId();
    }

    private boolean downloadSuccessful(long docId, @Nullable CancellationSignal signal) {
        OCFile file = mCurrentStorageManager.getFileById(docId);
        Intent i = new Intent(getContext(), FileDownloader.class);
        i.putExtra(FileDownloader.EXTRA_ACCOUNT, mCurrentStorageManager.getAccount());
        i.putExtra(FileDownloader.EXTRA_FILE, file);
        getContext().startService(i);

        do {
            if (!waitOrGetCancelled(signal)) {
                return false;
            }
            Log.i(TAG, "waiting...");
            file = mCurrentStorageManager.getFileById(docId);

        } while (!file.isDown());
        return true;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public AssetFileDescriptor openDocumentThumbnail(String documentId,
                                                     Point sizeHint,
                                                     CancellationSignal signal)
            throws FileNotFoundException {
        long docId = Long.parseLong(documentId);
        updateStorageManagerFromRootId(docId);

        OCFile file = mCurrentStorageManager.getFileById(docId);

        File realFile = new File(file.getStoragePath());

        return new AssetFileDescriptor(
                ParcelFileDescriptor.open(realFile, ParcelFileDescriptor.MODE_READ_ONLY),
                0,
                AssetFileDescriptor.UNKNOWN_LENGTH);
    }

    @Override
    public Cursor querySearchDocuments(String rootId, String query, String[] projection) throws FileNotFoundException {
        updateStorageManagerFromRootId(Long.parseLong(rootId));

        OCFile root = mCurrentStorageManager.getFileByPath("/");
        FileCursor result = new FileCursor(projection);

        for (OCFile f : findFiles(root, query)) {
            result.addFile(f);
        }

        return result;
    }

    private void updateStorageManagerFromDocId(long docId) {
        for (FileDataStorageManager manager : mRootIdToStorageManager.values()) {
            if (manager.getFileById(docId) != null) {
                mCurrentStorageManager = manager;
                break;
            }
        }
    }

    private void updateStorageManagerFromRootId(long rootId) {
        if (mCurrentStorageManager == null ||
                (mRootIdToStorageManager.containsKey(rootId) &&
                        mCurrentStorageManager != mRootIdToStorageManager.get(rootId))) {
            mCurrentStorageManager = mRootIdToStorageManager.get(rootId);
        }
    }

    private void initiateStorageMap() {
        mRootIdToStorageManager = new HashMap<>();

        ContentResolver contentResolver = getContext().getContentResolver();

        for (Account account : AccountUtils.getAccounts(getContext())) {
            final FileDataStorageManager storageManager =
                    new FileDataStorageManager(account, contentResolver);
            final OCFile rootDir = storageManager.getFileByPath("/");
            mRootIdToStorageManager.put(rootDir.getFileId(), storageManager);
        }

    }

    private boolean waitOrGetCancelled(CancellationSignal cancellationSignal) {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            return false;
        }

        return !(cancellationSignal != null && cancellationSignal.isCanceled());

    }

    Vector<OCFile> findFiles(OCFile root, String query) {
        Vector<OCFile> result = new Vector<>();
        for (OCFile f : mCurrentStorageManager.getFolderContent(root, false)) {
            if (f.isFolder()) {
                result.addAll(findFiles(f, query));
            } else if (f.getFileName().contains(query)) {
                result.add(f);
            }
        }
        return result;
    }
}
