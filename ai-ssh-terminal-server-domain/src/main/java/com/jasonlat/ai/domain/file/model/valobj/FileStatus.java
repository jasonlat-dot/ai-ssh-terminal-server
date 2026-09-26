package com.jasonlat.ai.domain.file.model.valobj;

public enum FileStatus {
    UPLOADING,
    UPLOADED,
    FAILED,
    /** 上传结果不确定或补偿删除失败，需要根据持久化的位置核对、再次清理。 */
    CLEANUP_REQUIRED
}
