package com.xspaceagi.file.sdk;

public interface IFileAccessService {

    String getFileUrlWithAk(String fileUrl);

    String getFileUrlWithAk(String siteUrl, String fileUrl);

    String getFileUrlWithAk(String fileUrl, boolean returnOriginalUrl);

    void checkFileUrlAk(String uri, String ak);

    void checkFileUrlAk0(String uri, String ak);

    String getRealFileUrl(String siteUrl, String fileUrl, boolean withAk, boolean returnOriginalUrl);

    String getRealFileUrl(String fileUrl);
}
