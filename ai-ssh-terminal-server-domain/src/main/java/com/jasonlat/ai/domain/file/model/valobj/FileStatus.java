package com.jasonlat.ai.domain.file.model.valobj;

/** 文件上传与补偿状态，名称直接保存到 file_asset.status。 */
public enum FileStatus {
    /** 元数据已创建，上传流程尚未完成；进程中断时也可能停留在此状态。 */
    UPLOADING,
    /** 对象、摘要和元数据均已保存，上传接口可以返回成功。 */
    UPLOADED,
    /** 上传后续流程失败，已确认上传的对象已执行补偿删除。 */
    FAILED,
    /** 上传结果不确定或补偿删除失败，需要根据持久化的位置核对、再次清理。 */
    CLEANUP_REQUIRED
}
