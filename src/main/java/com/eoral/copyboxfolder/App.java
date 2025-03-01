package com.eoral.copyboxfolder;

import com.box.sdk.*;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class App {

    private ExecutorService executor = null;

    public static void main(String[] args) {

        long startTime = System.currentTimeMillis();
        App app = new App();
        Properties properties = app.loadProperties();

        String boxConfigJsonStrToAccessSourceFolder = app.getFileContentAsString("box-config-to-access-source-folder.json");
        String sourceFolderId = properties.getProperty("sourceFolderId");
        String boxConfigJsonStrToAccessTargetFolder = app.getFileContentAsString("box-config-to-access-target-folder.json");
        String targetFolderId = properties.getProperty("targetFolderId");

        BoxLogger.defaultLogger().setLevelToAll();
        BoxDeveloperEditionAPIConnection sourceApi = app.createApi(boxConfigJsonStrToAccessSourceFolder);
        BoxDeveloperEditionAPIConnection targetApi = app.createApi(boxConfigJsonStrToAccessTargetFolder);

        app.initExecutor(properties);
        List<BoxItemMapping> boxItemMappingList = Collections.synchronizedList(new ArrayList<>());

        try {
            app.copyChildItems(sourceApi, sourceFolderId, targetApi, targetFolderId, boxItemMappingList);
        } finally {
            app.shutdownExecutorAndAwaitTermination(properties);
            long endTime = System.currentTimeMillis();
            Duration duration = Duration.of(endTime - startTime, ChronoUnit.MILLIS);
            System.out.println("\n\nCompleted in " + duration.toMinutes() + " minutes\n");
            Path exportFilePath = app.exportBoxItemMappingListToFile(boxItemMappingList);
            System.out.println("Output file is here: " + exportFilePath.toAbsolutePath());
        }
    }

    private Properties loadProperties() {
        Properties properties = new Properties();
        try (InputStream inputStream = this.getClass().getResourceAsStream("/app.properties")) {
            properties.load(inputStream);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return properties;
    }

    private String getFileContentAsString(String filename) {
        try (InputStream inputStream = this.getClass().getResourceAsStream("/" + filename)) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private BoxDeveloperEditionAPIConnection createApi(String jsonString) {
        BoxConfig boxConfig = BoxConfig.readFrom(jsonString);
        IAccessTokenCache accessTokenCache = new InMemoryLRUAccessTokenCache(100);
        return BoxDeveloperEditionAPIConnection.getAppEnterpriseConnection(boxConfig, accessTokenCache);
    }

    private void initExecutor(Properties properties) {
        int numberOfThreads = Integer.parseInt(properties.getProperty("numberOfThreadsForCopyingFiles"));
        executor = Executors.newFixedThreadPool(numberOfThreads);
    }

    /**
     * This code is taken from https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ExecutorService.html
     * Only timeouts are changed.
     */
    private void shutdownExecutorAndAwaitTermination(Properties properties) {
        System.out.println("Waiting threads to finish...");
        int timeoutInHours = Integer.parseInt(properties.getProperty("timeoutInHours"));
        executor.shutdown(); // Disable new tasks from being submitted
        try {
            // Wait a while for existing tasks to terminate
            if (!executor.awaitTermination(timeoutInHours, TimeUnit.HOURS)) {
                executor.shutdownNow(); // Cancel currently executing tasks
                // Wait a while for tasks to respond to being cancelled
                if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                    System.err.println("Executor did not terminate");
                }
            }
        } catch (InterruptedException ex) {
            // (Re-)Cancel if current thread also interrupted
            executor.shutdownNow();
            // Preserve interrupt status
            Thread.currentThread().interrupt();
        }
    }

    private Path exportBoxItemMappingListToFile(List<BoxItemMapping> boxItemMappingList) {
        String userHomeDirectory = System.getProperty("user.home");
        String fileName = generateExportFileName();
        Path filePath = Paths.get(userHomeDirectory, fileName);
        try {
            Files.writeString(filePath, Utils.convertToJsonString(boxItemMappingList),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return filePath;
    }

    private String generateExportFileName() {
        Instant timestamp = Instant.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd'-'HHmmss").withZone(ZoneId.systemDefault());
        return "copy-box-folder-output-" + formatter.format(timestamp) + ".json";
    }

    private void copyChildItems(
            BoxDeveloperEditionAPIConnection sourceApi, String sourceFolderId,
            BoxDeveloperEditionAPIConnection targetApi, String targetFolderId,
            List<BoxItemMapping> boxItemMappingList) {
        BoxFolder sourceFolder = new BoxFolder(sourceApi, sourceFolderId);
        long offset = 0;
        long limit = 100;
        while (true) {
            PartialCollection<BoxItem.Info> itemCollection = sourceFolder.getChildrenRange(offset, limit);
            if (itemCollection.isEmpty()) {
                break;
            } else {
                for (BoxItem.Info sourceItemInfo: itemCollection) {
                    copyItem(sourceApi, sourceItemInfo, targetApi, targetFolderId, boxItemMappingList);
                }
                if (itemCollection.size() < limit) {
                    break;
                }
                offset += limit;
            }
        }
    }

    private void copyItem(
            BoxDeveloperEditionAPIConnection sourceApi, BoxItem.Info sourceItemInfo,
            BoxDeveloperEditionAPIConnection targetApi, String targetFolderId,
            List<BoxItemMapping> boxItemMappingList) {
        if (sourceItemInfo instanceof BoxFile.Info) {
            String fileId = sourceItemInfo.getID();
            String fileName = sourceItemInfo.getName();
            long fileSize = sourceItemInfo.getSize();
            executor.submit(() -> {
                String copiedFileId = copyFileIfNotExists(sourceApi, fileId, fileName, fileSize, targetApi, targetFolderId);
                boxItemMappingList.add(new BoxItemMapping(BoxItemType.FILE, fileId, copiedFileId));
                System.out.println("File copied or already exists - sourceId: " + fileId + ", targetId: " + copiedFileId);
            });
        } else if (sourceItemInfo instanceof BoxFolder.Info) {
            String folderId = sourceItemInfo.getID();
            String folderName = sourceItemInfo.getName();
            String createdFolderId = createFolderIfNotExists(targetApi, targetFolderId, folderName);
            boxItemMappingList.add(new BoxItemMapping(BoxItemType.FOLDER, folderId, createdFolderId));
            System.out.println("Folder created or already exists - sourceId: " + folderId + ", targetId: " + createdFolderId);
            copyChildItems(sourceApi, folderId, targetApi, createdFolderId, boxItemMappingList);
        }
    }

    /**
     * Use copyFileIfNotExists method, it is faster.
     */
    @Deprecated
    private String copyFileIfNotExistsUsingSearchMethod(
            BoxDeveloperEditionAPIConnection sourceApi, String sourceFileId, String sourceFileName, long sourceFileSize,
            BoxDeveloperEditionAPIConnection targetApi, String targetFolderId) {
        BoxItem.Info foundFileInfo = findFile(targetApi, targetFolderId, sourceFileName);
        if (foundFileInfo != null) {
            return foundFileInfo.getID();
        } else {
            return downloadFromSourceAndUploadToTarget(
                    sourceApi, sourceFileId, sourceFileName, sourceFileSize,
                    targetApi, targetFolderId);
        }
    }

    private String copyFileIfNotExists(
            BoxDeveloperEditionAPIConnection sourceApi, String sourceFileId, String sourceFileName, long sourceFileSize,
            BoxDeveloperEditionAPIConnection targetApi, String targetFolderId) {
        String foundFileId = findFileIdUsingCanUploadMethod(targetApi, targetFolderId, sourceFileName);
        if (foundFileId != null) {
            return foundFileId;
        } else {
            return downloadFromSourceAndUploadToTarget(
                    sourceApi, sourceFileId, sourceFileName, sourceFileSize,
                    targetApi, targetFolderId);
        }
    }

    private String findFileIdUsingCanUploadMethod(BoxDeveloperEditionAPIConnection api, String parentFolderId, String name) {
        BoxFolder parentFolder = new BoxFolder(api, parentFolderId);
        try {
            long size = 1; // For our use case, any number greater than 0 is ok.
            parentFolder.canUpload(name, size);
            return null;
        } catch (BoxAPIException e) {
            String conflictingId = getConflictingIdIfItemNameInUse(e);
            if (conflictingId != null) {
                return conflictingId;
            } else {
                throw e;
            }
        }
    }

    private BoxItem.Info findFile(BoxDeveloperEditionAPIConnection api, String parentFolderId, String name) {
        return findItem(api, parentFolderId, "file", name);
    }

    private BoxItem.Info findFolder(BoxDeveloperEditionAPIConnection api, String parentFolderId, String name) {
        return findItem(api, parentFolderId, "folder", name);
    }

    // Immediately after creating a folder/file, search method may not find it. I have seen this issue a few times.
    // Probably, search method is using a cache in Box.
    private BoxItem.Info findItem(BoxDeveloperEditionAPIConnection api, String parentFolderId, String type, String name) {
        BoxSearch boxSearch = new BoxSearch(api);
        BoxSearchParameters searchParams = new BoxSearchParameters();
        searchParams.setType(type);
        // Search results will also include items within any subfolders of those ancestor folders.
        // So, we are checking direct parent-child relationship while iterating search results.
        searchParams.setAncestorFolderIds(Arrays.asList(parentFolderId));
        searchParams.setContentTypes(Arrays.asList("name"));
        searchParams.setQuery("\"" + name + "\""); // We are using a quoted query to get exact matches only.
        PartialCollection<BoxItem.Info> searchResults = boxSearch.searchRange(0, 100, searchParams);
        // Why do we need a loop here? Please, read this: A search for "Blue-Box" may return search results including
        // the sequence "blue.box", "Blue Box", and "Blue-Box"; any item containing the words Blue and Box consecutively, in the order specified.
        // For more details, visit https://developer.box.com/reference/get-search/#param-query
        List<BoxItem.Info> items = new ArrayList<>();
        for (BoxItem.Info info : searchResults) {
            if (info.getParent().getID().equals(parentFolderId) && info.getName().equals(name)) {
                items.add(info);
            }
        }
        if (items.size() == 0) {
            return null;
        } else if (items.size() == 1) {
            return items.get(0);
        } else {
            throw new RuntimeException("This shouldn't happen, there must be a bug in the code.");
        }
    }

    private String downloadFromSourceAndUploadToTarget(
            BoxDeveloperEditionAPIConnection sourceApi, String sourceFileId, String sourceFileName, long sourceFileSize,
            BoxDeveloperEditionAPIConnection targetApi, String targetFolderId) {
        if (sourceFileSize <= 104857600) { // 100 MB
            return downloadFromSourceAndUploadToTargetUsingMemory(sourceApi, sourceFileId, sourceFileName, targetApi, targetFolderId);
        } else {
            return downloadFromSourceAndUploadToTargetUsingTempFile(sourceApi, sourceFileId, sourceFileName, targetApi, targetFolderId);
        }
    }

    private String downloadFromSourceAndUploadToTargetUsingMemory(
            BoxDeveloperEditionAPIConnection sourceApi, String sourceFileId, String sourceFileName,
            BoxDeveloperEditionAPIConnection targetApi, String targetFolderId) {
        byte[] bytes = downloadFile(sourceApi, sourceFileId);
        BoxFile.Info uploadedFileInfo = uploadFile(targetApi, targetFolderId, sourceFileName, bytes);
        return uploadedFileInfo.getID();
    }

    private String downloadFromSourceAndUploadToTargetUsingTempFile(
            BoxDeveloperEditionAPIConnection sourceApi, String sourceFileId, String sourceFileName,
            BoxDeveloperEditionAPIConnection targetApi, String targetFolderId) {
        Path filePath = null;
        try {
            filePath = Utils.createTempFile();
            downloadFile(sourceApi, sourceFileId, filePath);
            BoxFile.Info uploadedFileInfo = uploadFile(targetApi, targetFolderId, sourceFileName, filePath);
            return uploadedFileInfo.getID();
        } finally {
            if (filePath != null) {
                Utils.deleteFile(filePath);
            }
        }
    }

    private void downloadFile(BoxDeveloperEditionAPIConnection api, String fileId, Path filePath) {
        BoxFile file = new BoxFile(api, fileId);
        try (FileOutputStream outputStream = new FileOutputStream(filePath.toFile())) {
            file.download(outputStream);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private byte[] downloadFile(BoxDeveloperEditionAPIConnection api, String fileId) {
        BoxFile file = new BoxFile(api, fileId);
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            file.download(outputStream);
            return outputStream.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private BoxFile.Info uploadFile(BoxDeveloperEditionAPIConnection api, String parentFolderId, String fileName, Path filePath) {
        BoxFolder parentFolder = new BoxFolder(api, parentFolderId);
        try (FileInputStream inputStream = new FileInputStream(filePath.toFile())) {
            return parentFolder.uploadFile(inputStream, fileName);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private BoxFile.Info uploadFile(BoxDeveloperEditionAPIConnection api, String parentFolderId, String fileName, byte[] bytes) {
        BoxFolder parentFolder = new BoxFolder(api, parentFolderId);
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(bytes)) {
            return parentFolder.uploadFile(inputStream, fileName);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Use createFolderIfNotExists method, it is faster.
     */
    private String createFolderIfNotExistsUsingSearchMethod(BoxDeveloperEditionAPIConnection api, String parentFolderId, String folderName) {
        BoxItem.Info foundFolderInfo = findFolder(api, parentFolderId, folderName);
        if (foundFolderInfo != null) {
            return foundFolderInfo.getID();
        } else {
            BoxFolder parentFolder = new BoxFolder(api, parentFolderId);
            BoxFolder.Info createdFolderInfo = parentFolder.createFolder(folderName);
            return createdFolderInfo.getID();
        }
    }

    private String createFolderIfNotExists(BoxDeveloperEditionAPIConnection api, String parentFolderId, String folderName) {
        BoxFolder parentFolder = new BoxFolder(api, parentFolderId);
        try {
            BoxFolder.Info folderInfo = parentFolder.createFolder(folderName);
            return folderInfo.getID();
        } catch (BoxAPIException e) {
            String conflictingId = getConflictingIdIfItemNameInUse(e);
            if (conflictingId != null) {
                return conflictingId;
            } else {
                throw e;
            }
        }
    }

    private String getConflictingIdIfItemNameInUse(BoxAPIException e) {
        String conflictingId = null;
        if (e.getResponseCode() == 409) {
            JsonNode jsonNodeRoot = Utils.convertToJsonNode(e.getResponse());
            JsonNode jsonNodeCode = jsonNodeRoot.get("code");
            if (jsonNodeCode.textValue().equals("item_name_in_use")) {
                JsonNode jsonNodeContextInfo = jsonNodeRoot.get("context_info");
                JsonNode jsonNodeConflicts = jsonNodeContextInfo.get("conflicts");
                if (jsonNodeConflicts.isObject()) {
                    conflictingId = jsonNodeConflicts.get("id").textValue();
                } else if (jsonNodeConflicts.isArray()) {
                    int counter = 0;
                    for (JsonNode jsonNode : jsonNodeConflicts) {
                        counter++;
                        conflictingId = jsonNode.get("id").textValue();
                    }
                    if (counter != 1) {
                        throw new RuntimeException("Unexpected json response, conflicts array should have only one item.");
                    }
                } else {
                    throw new RuntimeException("Unexpected json response, conflicts should be an object or an array.");
                }
                if (conflictingId == null || conflictingId.trim().length() == 0) {
                    throw new RuntimeException("Conflicting id is not found.");
                }
            }
        }
        return conflictingId;
    }
}
