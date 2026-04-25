package com.yangwei.demo;

import com.yangwei.fileupload.FileUploadClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UploadController {

    // 对接方只需要这一行，直接注入，什么都不用配
    @Autowired
    private FileUploadClient fileUploadClient;

    @PostMapping("/upload")
    public String upload(@RequestParam("fileName") String fileName) {
        byte[] mockContent = ("content of " + fileName).getBytes();
        String fileKey = fileUploadClient.upload(fileName, mockContent);
        return "上传成功，fileKey=" + fileKey;
    }
}
