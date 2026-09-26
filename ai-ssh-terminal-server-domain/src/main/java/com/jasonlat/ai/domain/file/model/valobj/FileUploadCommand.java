package com.jasonlat.ai.domain.file.model.valobj;

/**
 * 应用层传给文件领域服务的上传参数，文件正文通过独立的 InputStream 传递。
 *
 * @param originalName 客户端文件名，领域服务会去掉路径并检查长度、扩展名
 * @param contentType 客户端声明的 MIME 类型，可为空，不作为文件安全性的判断依据
 * @param size 上传文件的声明字节数，用于限额检查和实际读取长度核对
 * @param ownerId 服务端认证身份；无认证身份时为空，不使用前端自报的用户 ID
 */
public record FileUploadCommand(String originalName, String contentType, long size, String ownerId) {
}
