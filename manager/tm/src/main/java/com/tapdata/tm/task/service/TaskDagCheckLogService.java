package com.tapdata.tm.task.service;

import com.tapdata.tm.commons.task.dto.TaskDto;
import com.tapdata.tm.config.security.UserDetail;
import com.tapdata.tm.observability.dto.TaskLogDto;
import com.tapdata.tm.task.entity.TaskDagCheckLog;
import com.tapdata.tm.task.vo.TaskDagCheckLogVo;
import org.bson.types.ObjectId;

import java.util.List;

public interface TaskDagCheckLogService {
    TaskDagCheckLog save(TaskDagCheckLog log);

    List<TaskDagCheckLog> saveAll(List<TaskDagCheckLog> logs);

    List<TaskDagCheckLog> dagCheck(TaskDto taskDto, UserDetail userDetail, boolean onlySave);

    TaskDagCheckLogVo getLogs(TaskLogDto dto);

    void removeAllByTaskId(String taskId);
}
