package com.jasonlat.ai.domain.file.model.valobj;

/** 存储 SDK 的返回值经适配后进入领域层，不向上暴露 SDK 类型。 */
public record StoredObject(ObjectLocation location, String etag) {
}
