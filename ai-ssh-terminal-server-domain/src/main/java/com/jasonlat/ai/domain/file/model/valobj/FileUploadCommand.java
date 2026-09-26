package com.jasonlat.ai.domain.file.model.valobj;

/** ownerId 只接受服务端认证身份；没有认证身份时为空，不信任前端声明的用户 ID。 */
public record FileUploadCommand(String originalName, String contentType, long size, String ownerId) {
}
