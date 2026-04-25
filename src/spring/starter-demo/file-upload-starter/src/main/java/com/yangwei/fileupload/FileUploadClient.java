package com.yangwei.fileupload;

/**
 * 对接方只需注入这个类：
 *
 * @Autowired
 * private FileUploadClient fileUploadClient;
 *
 * fileUploadClient.upload("hello.txt", content);
 */
public class FileUploadClient {

    private final FileUploadProperties properties;

    public FileUploadClient(FileUploadProperties properties) {
        this.properties = properties;
    }

    /**
     * 模拟上传文件：
     * 真实场景：1.获取临时ak/sk → 2.上传BOS → 3.对接文件系统保存fileKey
     */
    public String upload(String fileName, byte[] content) {
        System.out.println("=== FileUploadClient 开始上传 ===");
        System.out.println("环境: " + properties.getEnv());
        System.out.println("文件系统地址: " + properties.getServerUrl());
        System.out.println("文件名: " + fileName);
        System.out.println("文件大小: " + content.length + " bytes");

        // 模拟生成 fileKey
        String fileKey = properties.getEnv() + "/" + System.currentTimeMillis() + "/" + fileName;

        System.out.println("上传成功，fileKey: " + fileKey);
        System.out.println("=================================");
        return fileKey;
    }
}
