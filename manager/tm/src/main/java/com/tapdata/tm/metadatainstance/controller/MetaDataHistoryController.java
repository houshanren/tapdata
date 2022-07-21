package com.tapdata.tm.metadatainstance.controller;

import com.tapdata.tm.base.controller.BaseController;
import com.tapdata.tm.base.dto.ResponseMessage;
import com.tapdata.tm.commons.schema.MetadataInstancesDto;
import com.tapdata.tm.metadatainstance.service.MetaDataHistoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping("api/metadata/history")
public class MetaDataHistoryController extends BaseController {

    @Autowired
    private MetaDataHistoryService metaDataHistoryService;
    @PostMapping
    public ResponseMessage<?> saveHistory(@RequestBody MetadataInstancesDto metadataInstancesDto) {
        metaDataHistoryService.saveHistory(metadataInstancesDto);
        return success();
    }

    /**
     *
     * @param qualifiedName 唯一名称
     * @param time 最近时间戳
     * @return
     */
    @GetMapping()
    public ResponseMessage<?> findByVersionTime(@RequestParam("qualifiedName") String qualifiedName, @RequestParam("time") Long time) {
        MetadataInstancesDto metadataInstancesDto = metaDataHistoryService.findByVersionTime(qualifiedName, time);
        return success(metadataInstancesDto);
    }
}
