package com.tapdata.tm.alarm.service.impl;

import cn.hutool.core.date.DateField;
import cn.hutool.core.date.DateTime;
import cn.hutool.core.date.DateUnit;
import cn.hutool.core.date.DateUtil;
import cn.hutool.extra.cglib.CglibUtil;
import cn.hutool.extra.spring.SpringUtil;
import cn.hutool.json.JSONUtil;
import com.alibaba.fastjson.JSON;
import com.google.common.collect.Maps;
import com.tapdata.tm.Settings.constant.CategoryEnum;
import com.tapdata.tm.Settings.constant.KeyEnum;
import com.tapdata.tm.Settings.dto.MailAccountDto;
import com.tapdata.tm.Settings.entity.Settings;
import com.tapdata.tm.Settings.service.AlarmSettingService;
import com.tapdata.tm.Settings.service.SettingsService;
import com.tapdata.tm.alarm.constant.AlarmMailTemplate;
import com.tapdata.tm.alarm.constant.AlarmStatusEnum;
import com.tapdata.tm.alarm.dto.*;
import com.tapdata.tm.alarm.entity.AlarmInfo;
import com.tapdata.tm.alarm.service.AlarmService;
import com.tapdata.tm.base.aop.MeasureAOP;
import com.tapdata.tm.base.dto.Page;
import com.tapdata.tm.base.dto.TmPageable;
import com.tapdata.tm.commons.base.dto.BaseDto;
import com.tapdata.tm.commons.dag.Node;
import com.tapdata.tm.commons.task.constant.AlarmKeyEnum;
import com.tapdata.tm.commons.task.constant.NotifyEnum;
import com.tapdata.tm.commons.task.dto.TaskDto;
import com.tapdata.tm.commons.task.dto.alarm.AlarmRuleDto;
import com.tapdata.tm.commons.task.dto.alarm.AlarmSettingDto;
import com.tapdata.tm.commons.task.dto.alarm.AlarmSettingVO;
import com.tapdata.tm.commons.util.ThrowableUtils;
import com.tapdata.tm.config.security.UserDetail;
import com.tapdata.tm.events.constant.Type;
import com.tapdata.tm.events.service.EventsService;
import com.tapdata.tm.inspect.dto.InspectDto;
import com.tapdata.tm.inspect.service.InspectService;
import com.tapdata.tm.message.constant.*;
import com.tapdata.tm.message.dto.MessageDto;
import com.tapdata.tm.message.entity.MessageEntity;
import com.tapdata.tm.message.service.MessageService;
import com.tapdata.tm.mp.service.MpService;
import com.tapdata.tm.sms.SmsService;
import com.tapdata.tm.task.service.TaskService;
import com.tapdata.tm.user.service.UserService;
import com.tapdata.tm.utils.*;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.bson.types.ObjectId;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.common.TemplateParserContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.stereotype.Service;

import java.text.MessageFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @author jiuyetx
 * @date 2022/9/7
 */
@Service
@Slf4j
@Setter(onMethod_ = {@Autowired})
public class AlarmServiceImpl implements AlarmService {

    private MongoTemplate mongoTemplate;
    private TaskService taskService;
		private InspectService inspectService;
    private AlarmSettingService alarmSettingService;
    private MessageService messageService;
    private SettingsService settingsService;
    private UserService userService;

    private SmsService smsService;

    private MpService mpService;

    MailUtils mailUtils;
    EventsService eventsService;
    private final static String MAIL_SUBJECT = "【Tapdata】";

    @Setter
    @Getter
    @Value("${alarm.channel.sms:true}")
    private boolean enableSms = true;


    @Override
    public void save(AlarmInfo info) {
        Criteria criteria = Criteria.where("taskId").is(info.getTaskId()).and("metric").is(info.getMetric().name());
        if (StringUtils.isNotBlank(info.getNodeId())) {
            criteria.and("nodeId").is(info.getNodeId());
        }
        Query query = new Query(criteria);
        AlarmInfo one = mongoTemplate.findOne(query, AlarmInfo.class);

        DateTime date = DateUtil.date();
        if (Objects.nonNull(one)) {
            info.setId(one.getId());
            info.setTally(one.getTally() + 1);
            info.setLastUpdAt(date);
            FunctionUtils.isTureOrFalse(Lists.of(AlarmStatusEnum.CLOESE, AlarmStatusEnum.RECOVER).contains(one.getStatus())).trueOrFalseHandle(
                    () -> info.setFirstOccurrenceTime(date),
                    () -> info.setFirstOccurrenceTime(one.getFirstOccurrenceTime())
            );
            info.setLastOccurrenceTime(date);
            if (Objects.nonNull(one.getLastNotifyTime()) && Objects.isNull(info.getLastNotifyTime())) {
                UserDetail userDetail = userService.loadUserById(MongoUtils.toObjectId(info.getUserId()));
                AlarmSettingDto alarmSettingDto = alarmSettingService.findByKey(info.getMetric(),userDetail);
                if (Objects.nonNull(alarmSettingDto)) {
                    DateTime lastNotifyTime = DateUtil.offset(one.getLastNotifyTime(), parseDateUnit(alarmSettingDto.getUnit()), alarmSettingDto.getInterval());
                    if (date.after(lastNotifyTime)) {
                        info.setLastNotifyTime(date);
                    }else {
                        info.setLastNotifyTime(one.getLastNotifyTime());
                    }
                }
            } else {
                info.setLastNotifyTime(date);
            }

            mongoTemplate.save(info);
        } else {
            info.setFirstOccurrenceTime(date);
            info.setLastOccurrenceTime(date);
            info.setLastNotifyTime(date);
            mongoTemplate.insert(info);
        }
    }

    @Override
    public boolean checkOpen(TaskDto taskDto, String nodeId, AlarmKeyEnum key, NotifyEnum type, UserDetail userDetail) {
        boolean openTask = false;
        if (AlarmKeyEnum.SYSTEM_FLOW_EGINGE_DOWN.equals(key)) {
            openTask = true;
        } else if (Objects.nonNull(taskDto) && CollectionUtils.isNotEmpty(taskDto.getAlarmSettings())) {
            List<AlarmSettingDto> alarmSettingDtos = getAlarmSettingDtos(taskDto, nodeId);
            if (CollectionUtils.isNotEmpty(alarmSettingDtos)) {
                openTask = alarmSettingDtos.stream().anyMatch(t ->
                        t.getKey().equals(key) && t.isOpen() && (type ==null || t.getNotify().contains(type)));
            }
        }

        boolean openSys = false;
        List<AlarmSettingDto> all = alarmSettingService.findAllAlarmSetting(userDetail);
        if (CollectionUtils.isNotEmpty(all)) {
            openSys = all.stream().anyMatch(t ->
                    t.getKey().equals(key) && t.isOpen() && (type == null ||  t.getNotify().contains(type)));
        }

        return openTask && openSys;
    }

	public boolean checkOpen(List<AlarmSettingVO> alarmSettingVOS, AlarmKeyEnum key, NotifyEnum type, UserDetail userDetail) {
		boolean openTask = false;
		if (CollectionUtils.isNotEmpty(alarmSettingVOS)) {
			openTask = alarmSettingVOS.stream().anyMatch(t ->
							t.getKey().equals(key) && t.isOpen() && (type == null || t.getNotify().contains(type)));
		}
		boolean openSys = false;
		List<AlarmSettingDto> all = alarmSettingService.findAllAlarmSetting(userDetail);
		if (CollectionUtils.isNotEmpty(all)) {
			openSys = all.stream().anyMatch(t ->
							t.getKey().equals(key) && t.isOpen() && (type == null ||  t.getNotify().contains(type)));
		}

		return openTask && openSys;
	}

	@Override
	public void closeWhenInspectTaskRunning(String id) {
		if (StringUtils.isNotEmpty(id)) {
			Update update = Update.update("status", AlarmStatusEnum.CLOESE);
			mongoTemplate.updateMulti(Query.query(Criteria.where("inspectId").is(id)), update, AlarmInfo.class);
		}
	}

	@NotNull
    @SuppressWarnings("unchecked")
    private static List<AlarmSettingDto> getAlarmSettingDtos(TaskDto taskDto, String nodeId) {
        List<AlarmSettingDto> alarmSettingDtos = Lists.newArrayList();
        Optional.ofNullable(taskDto.getAlarmSettings()).ifPresent(list -> {
            alarmSettingDtos.addAll(CglibUtil.copyList(list, AlarmSettingDto::new));
        });

        if (Objects.nonNull(nodeId)) {
            for (Node node : taskDto.getDag().getNodes()) {
                if (node.getId().equals(nodeId) && CollectionUtils.isNotEmpty(node.getAlarmSettings())) {
                    alarmSettingDtos.addAll(CglibUtil.copyList(node.getAlarmSettings(), AlarmSettingDto::new));
                    break;
                }
            }
        } else {
            taskDto.getDag().getNodes().forEach(node -> {
                Optional.ofNullable(node.getAlarmSettings()).ifPresent(list -> {
                    alarmSettingDtos.addAll(CglibUtil.copyList(list, AlarmSettingDto::new));
                });
            });
        }
        return alarmSettingDtos;
    }

    @Override
    @Nullable
    @SuppressWarnings("unchecked")
    public Map<String, List<AlarmRuleDto>> getAlarmRuleDtos(TaskDto taskDto) {
        if (Objects.nonNull(taskDto)) {
            Map<String, List<AlarmRuleDto>> ruleMap = Maps.newHashMap();
            Optional.ofNullable(taskDto.getAlarmRules()).ifPresent(list -> ruleMap.put(taskDto.getId().toHexString(), CglibUtil.copyList(list, AlarmRuleDto::new)));

            taskDto.getDag().getNodes().forEach(node -> {
                Optional.ofNullable(node.getAlarmRules()).ifPresent(list -> ruleMap.put(node.getId(), CglibUtil.copyList(list, AlarmRuleDto::new)));
            });
            return ruleMap;
        }
        return null;
    }

    @Override
    public void notifyAlarm() {
        Criteria criteria = Criteria.where("status").is(AlarmStatusEnum.ING)
                .and("lastNotifyTime").lt(DateUtil.date()).gt(DateUtil.offsetSecond(DateUtil.date(), -30)
                );
        Query needNotifyQuery = new Query(criteria);
        needNotifyQuery.with(Sort.by("lastNotifyTime").ascending());
        List<AlarmInfo> alarmInfos = mongoTemplate.find(needNotifyQuery, AlarmInfo.class);

        if (CollectionUtils.isEmpty(alarmInfos)) {
            return;
        }

        List<String> taskIds = alarmInfos.stream().map(AlarmInfo::getTaskId).filter(StringUtils::isNotEmpty).collect(Collectors.toList());
				List<TaskDto> tasks = new ArrayList<>();
				if (CollectionUtils.isNotEmpty(taskIds)) {
					tasks = taskService.findAllTasksByIds(taskIds);
					if (CollectionUtils.isEmpty(tasks)) {
						tasks = new ArrayList<>();
					}
				}

			List<String> inspectIds = alarmInfos.stream().map(AlarmInfo::getInspectId).filter(StringUtils::isNotEmpty).collect(Collectors.toList());
			List<InspectDto> inspectDtos = new ArrayList<>();
			if (CollectionUtils.isNotEmpty(inspectIds)) {
				inspectDtos = inspectService.findAllByIds(inspectIds);
				if (CollectionUtils.isEmpty(inspectDtos)) {
					inspectDtos = new ArrayList<>();
				}
			}

			Map<String, TaskDto> taskDtoMap = tasks.stream()
                .filter(t -> !t.is_deleted())
                .collect(Collectors.toMap(t -> t.getId().toHexString(), Function.identity(), (e1, e2) -> e1));
			Map<String, InspectDto> inspectDtoMap = inspectDtos.stream().filter(t -> t.getIs_deleted() == null || !t.getIs_deleted())
							.collect(Collectors.toMap(t -> t.getId().toHexString(), Function.identity(), (e1, e2) -> e1));

			List<String> userIds = tasks.stream().map(TaskDto::getUserId).distinct().collect(Collectors.toList());
			List<String> inspectUserIds = inspectDtos.stream().map(BaseDto::getUserId).distinct().collect(Collectors.toList());
			userIds.addAll(inspectUserIds);

			List<UserDetail> userByIdList = userService.getUserByIdList(userIds);
        Map<String, UserDetail> userDetailMap = userByIdList.stream().collect(Collectors.toMap(UserDetail::getUserId, Function.identity(), (e1, e2) -> e1));

        for (AlarmInfo info : alarmInfos) {
            if (AlarmKeyEnum.SYSTEM_FLOW_EGINGE_DOWN.equals(info.getMetric())) {
                continue;
            }

					UserDetail userDetail;
					AlarmMessageDto alarmMessageDto;
					if (StringUtils.isNotEmpty(info.getTaskId())) {

						TaskDto taskDto = taskDtoMap.get(info.getTaskId());
						if (taskDto == null) {
							continue;
						}
						userDetail = userDetailMap.get(taskDto.getUserId());
						alarmMessageDto = AlarmMessageDto.builder()
										.agentId(taskDto.getAgentId())
										.userId(taskDto.getUserId())
										.name(taskDto.getName())
										.systemOpen(checkOpen(taskDto, info.getNodeId(), info.getMetric(), NotifyEnum.SYSTEM, userDetail))
										.emailOpen(checkOpen(taskDto, info.getNodeId(), info.getMetric(), NotifyEnum.EMAIL, userDetail))
										.smsOpen(checkOpen(taskDto, info.getNodeId(), info.getMetric(), NotifyEnum.SMS, userDetail))
										.wechatOpen(checkOpen(taskDto, info.getNodeId(), info.getMetric(), NotifyEnum.WECHAT, userDetail))
										.build();

					} else if (StringUtils.isNotEmpty(info.getInspectId())) {
						InspectDto inspectDto = inspectDtoMap.get(info.getInspectId());
						if (inspectDto == null) {
							continue;
						}
						userDetail = userDetailMap.get(inspectDto.getUserId());
						alarmMessageDto = AlarmMessageDto.builder()
										.agentId(inspectDto.getAgentId())
										.userId(inspectDto.getUserId())
										.name(inspectDto.getName())
										.systemOpen(checkOpen(inspectDto.getAlarmSettings(),  info.getMetric(), NotifyEnum.SYSTEM, userDetail))
										.emailOpen(checkOpen(inspectDto.getAlarmSettings(),  info.getMetric(), NotifyEnum.EMAIL, userDetail))
										.smsOpen(checkOpen(inspectDto.getAlarmSettings(),  info.getMetric(), NotifyEnum.SMS, userDetail))
										.wechatOpen(checkOpen(inspectDto.getAlarmSettings(),  info.getMetric(), NotifyEnum.WECHAT, userDetail))
										.build();

					} else {
						log.warn("not support...");
						continue;
					}

            FunctionUtils.ignoreAnyError(() -> {
                boolean reuslt = sendMessage(info, alarmMessageDto, userDetail,null);
                if (!reuslt) {
                    DateTime dateTime = DateUtil.offsetSecond(info.getLastNotifyTime(),30);
                    info.setLastNotifyTime(dateTime);
                    save(info);
                }
            });
            FunctionUtils.ignoreAnyError(() -> {
                boolean reuslt = sendMail(info, alarmMessageDto, userDetail, null);
                if (!reuslt) {
                    DateTime dateTime = DateUtil.offsetSecond(info.getLastNotifyTime(), 30);
                    info.setLastNotifyTime(dateTime);
                    save(info);
                }
            });

            boolean isCloud = settingsService.isCloud();
            if (isCloud) {
                FunctionUtils.ignoreAnyError(() -> {
                    boolean reuslt = sendSms(info, alarmMessageDto, userDetail, null);
                    if (!reuslt) {
                        DateTime dateTime = DateUtil.offsetSecond(info.getLastNotifyTime(), 30);
                        info.setLastNotifyTime(dateTime);
                        save(info);
                    }
                });
                FunctionUtils.ignoreAnyError(() -> {
                    boolean reuslt = sendWeChat(info, alarmMessageDto, userDetail, null);
                    if (!reuslt) {
                        DateTime dateTime = DateUtil.offsetSecond(info.getLastNotifyTime(), 30);
                        info.setLastNotifyTime(dateTime);
                        save(info);
                    }
                });
            }
        }

    }

    private boolean sendMessage(AlarmInfo info, AlarmMessageDto alarmMessageDto, UserDetail userDetail, MessageDto messageDto) {
        try {
            MessageEntity messageEntity = new MessageEntity();
            Date date = DateUtil.date();
            if (messageDto == null) {
                if (!alarmMessageDto.isSystemOpen()) {
                    log.info("Current user ({}, {}) can't open system notice, cancel send message", userDetail.getUsername(), userDetail.getUserId());
                    return true;
                }
                ExpressionParser parser = new SpelExpressionParser();
                TemplateParserContext parserContext = new TemplateParserContext();

                messageEntity.setTemplate(info.getSummary());
                String template = MessageUtil.getAlarmMsg(Locale.US, info.getSummary());
                String content = parser.parseExpression(template, parserContext).getValue(info.getParam(), String.class);

                String taskId = alarmMessageDto.getTaskId();
                messageEntity.setLevel(info.getLevel().name());
                messageEntity.setAgentId(alarmMessageDto.getAgentId());
                messageEntity.setServerName(alarmMessageDto.getAgentId());
                messageEntity.setMsg(MsgTypeEnum.ALARM.getValue());

                messageEntity.setTitle(content);
                MessageMetadata metadata = new MessageMetadata(alarmMessageDto.getName(), taskId);
                messageEntity.setMessageMetadata(metadata);
                messageEntity.setSystem(SystemEnum.MIGRATION.getValue());
                messageEntity.setUserId(alarmMessageDto.getUserId());
                messageEntity.setRead(false);
                messageEntity.setParam(info.getParam());
            } else {
                String msgType = messageDto.getMsg();
                AlarmKeyEnum alarmKeyEnum;
                alarmKeyEnum = AlarmKeyEnum.SYSTEM_FLOW_EGINGE_DOWN;
                if (MsgTypeEnum.CONNECTED.getValue().equals(msgType)) {
                    alarmKeyEnum = AlarmKeyEnum.SYSTEM_FLOW_EGINGE_UP;
                }
                if (!isOwnPermission(userDetail, alarmKeyEnum, NotifyEnum.SYSTEM)) {
                    log.info("Current user ({}, {}) can't open system notice, cancel send message", userDetail.getUsername(), userDetail.getUserId());
                    return true;
                }
                messageEntity.setLevel(Level.EMERGENCY.name());
                messageEntity.setAgentId(messageDto.getAgentId());
                messageEntity.setServerName(messageDto.getAgentId());
                messageEntity.setMsg(MsgTypeEnum.ALARM.getValue());
                String content = "";
                MessageMetadata messageMetadata = JSONUtil.toBean(messageDto.getMessageMetadata(), MessageMetadata.class);
                //目前msgNotification 只会有三种情况
                String metadataName = messageMetadata.getName();
                if (SourceModuleEnum.AGENT.getValue().equalsIgnoreCase(messageDto.getSourceModule())) {
                    if (MsgTypeEnum.CONNECTED.getValue().equals(msgType)) {
                        content = "尊敬的用户，你好，您在 Tapdata Cloud V3.0 上创建的实例:" + metadataName + " 已上线运行";
                    } else if (MsgTypeEnum.CONNECTION_INTERRUPTED.getValue().equals(msgType)) {
                        content = "尊敬的用户，你好，您在 Tapdata Cloud V3.0 上创建的实例:" + metadataName + " 已离线，请及时处理";
                    }
                } else {
                    if (MsgTypeEnum.CONNECTED.getValue().equals(msgType)) {
                        content = "尊敬的用户，你好，您在Tapdata Cloud 上创建的任务:" + metadataName + " 正在运行";
                    } else if (MsgTypeEnum.CONNECTION_INTERRUPTED.getValue().equals(msgType)) {
                        content = "您在Tapdata Cloud 上创建的任务:" + metadataName + " 出错，请及时处理";
                    }
                }
                String summary = content;
                messageEntity.setTitle(summary);
                messageEntity.setMessageMetadata(messageDto.getMessageMetadataObject());
                messageEntity.setSystem(SystemEnum.AGENT.getValue());
            }
            messageEntity.setCreateAt(date);
            messageEntity.setLastUpdAt(date);
            messageEntity.setRead(false);
            messageService.addMessage(messageEntity, userDetail);
        } catch (Exception e) {
            log.error("sendMessage error: {}", ThrowableUtils.getStackTraceByPn(e));
            return true;
        }
        return true;
    }

    private boolean sendMail(AlarmInfo info, AlarmMessageDto alarmMessageDto, UserDetail userDetail, MessageDto messageDto) {
        try {
            String title = null;
            String content = null;
            MailAccountDto mailAccount;
            if (messageDto == null) {
                if (!alarmMessageDto.isEmailOpen()) {
                    log.error("Current user ({}, {}) can't bind email, cancel send message {}.", userDetail.getUsername(), userDetail.getUserId(), JSON.toJSONString(info));
                    return true;
                }
                mailAccount = getMailAccount(alarmMessageDto.getUserId());
                Map<String, String> map = getTaskTitleAndContent(info);
                content = map.get("content");
                title = map.get("title");
            } else {
                mailAccount = getMailAccount(messageDto.getUserId());
                String msgType = messageDto.getMsg();
                AlarmKeyEnum alarmKeyEnum;
                alarmKeyEnum = AlarmKeyEnum.SYSTEM_FLOW_EGINGE_DOWN;
                if(MsgTypeEnum.CONNECTED.getValue().equals(msgType)) {
                    alarmKeyEnum = AlarmKeyEnum.SYSTEM_FLOW_EGINGE_UP;
                }
                if (!isOwnPermission(userDetail, alarmKeyEnum, NotifyEnum.EMAIL)) {
                    log.info("Current user ({}, {}) can't open sms notice, cancel send message", userDetail.getUsername(), userDetail.getUserId());
                    return true;
                }
                MessageMetadata messageMetadata = JSONUtil.toBean(messageDto.getMessageMetadata(), MessageMetadata.class);
                //目前msgNotification 只会有三种情况
                String metadataName = messageMetadata.getName();
                if (SourceModuleEnum.AGENT.getValue().equalsIgnoreCase(messageDto.getSourceModule())) {
                    if (MsgTypeEnum.CONNECTED.getValue().equals(msgType)) {
                        title = "实例上线";
                        content = "尊敬的用户，你好，您在 Tapdata Cloud V3.0 上创建的实例:" + metadataName + " 已上线运行";
                    } else if (MsgTypeEnum.CONNECTION_INTERRUPTED.getValue().equals(msgType)) {
                        title = "实例离线";
                        content = "尊敬的用户，你好，您在 Tapdata Cloud V3.0 上创建的实例:" + metadataName + " 已离线，请及时处理";
                    }
                } else {
                    if (MsgTypeEnum.CONNECTED.getValue().equals(msgType)) {
                        title = "状态变为运行中";
                        content = "尊敬的用户，你好，您在Tapdata Cloud 上创建的任务:" + metadataName + " 正在运行";
                    } else if (MsgTypeEnum.CONNECTION_INTERRUPTED.getValue().equals(msgType)) {
                        title = "状态已由运行中变为离线，可能会影响您的任务正常运行，请及时处理。";
                        content = "您在Tapdata Cloud 上创建的任务:" + metadataName + " 出错，请及时处理";
                    } else if (MsgTypeEnum.STOPPED_BY_ERROR.getValue().equals(msgType)) {

                    }
                }
            }
            log.info("send sendMail mailAccount：{} ",mailAccount);
            if (Objects.nonNull(title)) {
                Settings prefix = settingsService.getByCategoryAndKey(CategoryEnum.SMTP, KeyEnum.EMAIL_TITLE_PREFIX);
                AtomicReference<String> mailTitle = new AtomicReference<>(title);
                Optional.ofNullable(prefix).ifPresent(pre -> mailTitle.updateAndGet(v -> pre.getValue() + v));
                MailUtils.sendHtmlEmail(mailAccount, mailAccount.getReceivers(), mailTitle.get(), content);
            }
        } catch (Exception e) {
            log.error("sendMail error: {}", ThrowableUtils.getStackTraceByPn(e));
            return true;
        }
        return true;
    }


    public Map<String, String> getTaskTitleAndContent(AlarmInfo info) {
        String title;
        String content;
        String dateTime = DateUtil.formatDateTime(info.getLastOccurrenceTime());
        String SmsEvent;
        switch (info.getMetric()) {
            case TASK_STATUS_ERROR:
                title = MessageFormat.format(AlarmMailTemplate.TASK_STATUS_STOP_ERROR_TITLE, info.getName());
                content = MessageFormat.format(AlarmMailTemplate.TASK_STATUS_STOP_ERROR, info.getName(), dateTime);
                SmsEvent = "任务错误";
                break;
            case TASK_FULL_COMPLETE:
                title = MessageFormat.format(AlarmMailTemplate.TASK_FULL_COMPLETE_TITLE, info.getName());
                content = MessageFormat.format(AlarmMailTemplate.TASK_FULL_COMPLETE, info.getName(), info.getParam().get("snapDoneDate"));
                SmsEvent = "全量结束";
                break;
            case TASK_INCREMENT_START:
                title = MessageFormat.format(AlarmMailTemplate.TASK_INCREMENT_START_TITLE, info.getName());
                content = MessageFormat.format(AlarmMailTemplate.TASK_INCREMENT_START, info.getName(), info.getParam().get("cdcTime"));
                SmsEvent = "增量开始";
                break;
            case TASK_INCREMENT_DELAY:
                title = MessageFormat.format(AlarmMailTemplate.TASK_INCREMENT_DELAY_START_TITLE, info.getName());
                content = MessageFormat.format(AlarmMailTemplate.TASK_INCREMENT_DELAY_START, info.getName(), info.getParam().get("currentValue"));
                SmsEvent = "增量延迟";
                break;
            case DATANODE_AVERAGE_HANDLE_CONSUME:
                title = MessageFormat.format(AlarmMailTemplate.AVERAGE_HANDLE_CONSUME_TITLE, info.getName());
                content = MessageFormat.format(AlarmMailTemplate.AVERAGE_HANDLE_CONSUME, info.getName(), info.getNode(), info.getParam().get("currentValue"), info.getParam().get("threshold"), dateTime);
                SmsEvent = "当前任务运行超过阈值";
                break;
            case PROCESSNODE_AVERAGE_HANDLE_CONSUME:
                title = MessageFormat.format(AlarmMailTemplate.AVERAGE_HANDLE_CONSUME_TITLE, info.getName());
                content = MessageFormat.format(AlarmMailTemplate.AVERAGE_HANDLE_CONSUME, info.getName(), info.getNode(),
                        info.getParam().get("threshold"), info.getParam().get("currentValue"), dateTime);
                SmsEvent = "当前任务运行超过阈值";
                break;
					case INSPECT_TASK_ERROR:
						title = MessageFormat.format(AlarmMailTemplate.INSPECT_TASK_ERROR_TITLE, info.getParam().get("inspectName"));
						content = MessageFormat.format(AlarmMailTemplate.INSPECT_TASK_ERROR_CONTENT,
										info.getParam().get("inspectName"), info.getParam().get("alarmDate"));
						SmsEvent ="校验任务异常";
						break;
					case INSPECT_COUNT_ERROR:
						title = MessageFormat.format(AlarmMailTemplate.INSPECT_COUNT_ERROR_TITLE, info.getParam().get("inspectName"));
						content = MessageFormat.format(AlarmMailTemplate.INSPECT_COUNT_ERROR_CONTENT,
										info.getParam().get("inspectName"), info.getParam().get("count"));
						SmsEvent ="快速count校验不一致告警";
						break;
					case INSPECT_VALUE_ERROR:
						if ("INSPECT_VALUE_JOIN_ERROR".equals(info.getSummary())) {
							title = MessageFormat.format(AlarmMailTemplate.INSPECT_VALUE_ERROR_JOIN_TITLE, info.getParam().get("inspectName"));
							content = MessageFormat.format(AlarmMailTemplate.INSPECT_VALUE_ERROR_JOIN_CONTENT,
											info.getParam().get("inspectName"), info.getParam().get("count"));
							SmsEvent ="关联字段值校验结果不一致告警";
						} else {
							title = MessageFormat.format(AlarmMailTemplate.INSPECT_VALUE_ERROR_ALL_TITLE, info.getParam().get("inspectName"));
							content = MessageFormat.format(AlarmMailTemplate.INSPECT_VALUE_ERROR_ALL_CONTENT,
											info.getParam().get("inspectName"), info.getParam().get("count"));
							SmsEvent ="表全字段值校验结果不一致告警";
						}
						break;
            default:
                title=info.getName()+"发生异常";
                content = StringUtils.replace(info.getSummary(), "$taskName", info.getName());
                SmsEvent ="异常";
        }
        Map<String, Object> oemConfig = OEMReplaceUtil.getOEMConfigMap("email/replace.json");
        title = OEMReplaceUtil.replace(title, oemConfig);
        content = OEMReplaceUtil.replace(content, oemConfig);
        Map map = new HashMap();
        map.put("title", title);
        map.put("content", content);
        map.put("smsEvent", SmsEvent);
        return map;
    }

    /**
     * 是否用发送权限
     * @return
     */
    private boolean isOwnPermission(UserDetail userDetail, AlarmKeyEnum key, NotifyEnum type) {
        boolean permission = false;
        List<AlarmSettingDto> all = alarmSettingService.findAllAlarmSetting(userDetail);
        if (CollectionUtils.isNotEmpty(all)) {
            permission = all.stream().anyMatch(t ->
                    t.getKey().equals(key) && t.isOpen() && t.getNotify().contains(type));
        }
        return  permission;
    }

    private boolean sendSms(AlarmInfo info, AlarmMessageDto alarmMessageDto, UserDetail userDetail, MessageDto messageDto) {
        try {
            if (StringUtils.isEmpty(userDetail.getPhone())) {
                log.error("Current user ({}, {}) can't bind phone, cancel send message.", userDetail.getUsername(), userDetail.getUserId());
                return true;
            }
            String smsTemplateCode = "";
            String system = "task";
            String phone = userDetail.getPhone();
            String metadataName = "";
            String smsContent = "";
            String templateParam="";
            // task alarm
            if (messageDto == null) {
                if (!alarmMessageDto.isSmsOpen()) {
                    log.info("Current user ({}, {}) can't open sms notice, cancel send message", userDetail.getUsername(), userDetail.getUserId());
                    return true;
                }
                Map<String, String> map = getTaskTitleAndContent(info);
                String smsEvent = map.get("smsEvent");
                if (info.getMetric().equals(AlarmKeyEnum.TASK_FULL_COMPLETE) || info.getMetric().equals(AlarmKeyEnum.TASK_INCREMENT_START)) {
                    smsTemplateCode = SmsService.TASK_NOTICE;
                    templateParam = "{\"JobName\":\"" + "【" + alarmMessageDto.getName() + "】" + smsEvent + "\"}";
                } else {
                    templateParam = "{\"JobName\":\"" + "【" + alarmMessageDto.getName() + "】" + "\",\"eventName\":\"" + smsEvent + "\"}";
                    smsTemplateCode = SmsService.TASK_ABNORMITY_NOTICE;
                }
                smsContent = map.get("content");
            } else {
                String msgType = messageDto.getMsg();
                AlarmKeyEnum alarmKeyEnum;
                alarmKeyEnum = AlarmKeyEnum.SYSTEM_FLOW_EGINGE_DOWN;
               if(MsgTypeEnum.CONNECTED.getValue().equals(msgType)) {
                    alarmKeyEnum = AlarmKeyEnum.SYSTEM_FLOW_EGINGE_UP;
               }
                if (!isOwnPermission(userDetail, alarmKeyEnum, NotifyEnum.SMS)) {
                    log.info("Current user ({}, {}) can't open sms notice, cancel send message", userDetail.getUsername(), userDetail.getUserId());
                    return true;
                }
                MessageMetadata messageMetadata = JSONUtil.toBean(messageDto.getMessageMetadata(), MessageMetadata.class);
                //目前msgNotification 只会有三种情况
                metadataName = messageMetadata.getName();
                if (SourceModuleEnum.AGENT.getValue().equalsIgnoreCase(messageDto.getSourceModule())) {
                    if (MsgTypeEnum.CONNECTED.getValue().equals(msgType)) {
                        smsContent = "尊敬的用户，你好，您在 Tapdata Cloud V3.0 上创建的实例:" + metadataName + " 已上线运行";
                    } else if (MsgTypeEnum.CONNECTION_INTERRUPTED.getValue().equals(msgType)) {
                        smsContent = "尊敬的用户，你好，您在 Tapdata Cloud V3.0 上创建的实例:" + metadataName + " 已离线，请及时处理";
                    }
                } else {
                    if (MsgTypeEnum.CONNECTED.getValue().equals(msgType)) {
                        smsContent = "尊敬的用户，你好，您在Tapdata Cloud 上创建的任务:" + metadataName + " 正在运行";
                    } else if (MsgTypeEnum.CONNECTION_INTERRUPTED.getValue().equals(msgType)) {
                        smsContent = "尊敬的用户，你好，您在Tapdata Cloud 上创建的任务:" + metadataName + " 出错，请及时处理";
                    }
                }
                smsTemplateCode = smsService.getTemplateCode(messageDto);
                system = messageDto.getSystem();
                String telParamName = SmsService.getTelParamNameByMessageSystem(system);
                templateParam ="{\"" + telParamName + "\":\"" + metadataName + "\"}";
            }
            log.info("发送短信通知{}", phone);
            SendStatus sendStatus = smsService.sendShortMessage(smsTemplateCode, phone, templateParam);
            if (messageDto != null) {
                eventsService.recordEvents(MAIL_SUBJECT, smsContent, phone, messageDto, sendStatus, 1, Type.NOTICE_SMS);
            }
        } catch (Exception e) {
            log.error("sendSms error: {}", ThrowableUtils.getStackTraceByPn(e));
            return true;
        }
        return true;
    }





    private boolean sendWeChat(AlarmInfo info, AlarmMessageDto alarmMessageDto, UserDetail userDetail, MessageDto messageDto) {
        try {
            String openId = userDetail.getOpenid();
            if (StringUtils.isBlank(openId)) {
                log.error("Current user ({}, {}) can't bind weChat, cancel push message.", userDetail.getUsername(), userDetail.getUserId());
                return true;
            }
            String metadataName = "";
            String title = "";
            String content = "";
            // 任务级别的告警
            if (messageDto == null) {
                if (!alarmMessageDto.isWechatOpen()) {
                    log.info("Current user ({}, {}) can't open weChat notice, cancel send message", userDetail.getUsername(), userDetail.getUserId());
                    return true;
                }
                log.info("sendWeChat");
                Map<String, String> map = getTaskTitleAndContent(info);
                content = map.get("content");
                title = map.get("title");
            } else {
                String msgType = messageDto.getMsg();
                AlarmKeyEnum alarmKeyEnum;
                alarmKeyEnum = AlarmKeyEnum.SYSTEM_FLOW_EGINGE_DOWN;
                if(MsgTypeEnum.CONNECTED.getValue().equals(msgType)) {
                    alarmKeyEnum = AlarmKeyEnum.SYSTEM_FLOW_EGINGE_UP;
                }
                if (!isOwnPermission(userDetail, alarmKeyEnum, NotifyEnum.WECHAT)) {
                    log.info("Current user ({}, {}) can't open weChat notice, cancel send message", userDetail.getUsername(), userDetail.getUserId());
                    return true;
                }
                MessageMetadata messageMetadata = JSONUtil.toBean(messageDto.getMessageMetadata(), MessageMetadata.class);
                //目前msgNotification 只会有三种情况
                metadataName = messageMetadata.getName();
                if (SourceModuleEnum.AGENT.getValue().equalsIgnoreCase(messageDto.getSourceModule())) {
                    if (MsgTypeEnum.CONNECTED.getValue().equals(msgType)) {
                        title = "实例 " + metadataName + "已上线运行";
                        content = "尊敬的用户，你好，您在 Tapdata Cloud V3.0 上创建的实例:" + metadataName + " 已上线运行";
                    } else if (MsgTypeEnum.CONNECTION_INTERRUPTED.getValue().equals(msgType)) {
                        title = "实例 " + metadataName + "已离线";
                        content = "尊敬的用户，你好，您在 Tapdata Cloud V3.0 上创建的实例:" + metadataName + " 已离线，请及时处理";
                    }
                } else {
                    if (MsgTypeEnum.CONNECTED.getValue().equals(msgType)) {
                        title = "任务:" + metadataName + " 正在运行";
                        content = "尊敬的用户，你好，您在Tapdata Cloud 上创建的任务:" + metadataName + " 正在运行";
                    } else if (MsgTypeEnum.CONNECTION_INTERRUPTED.getValue().equals(msgType)) {
                        title = "任务:" + metadataName + " 出错";
                        content = "您在Tapdata Cloud 上创建的任务:" + metadataName + " 出错，请及时处理";
                    }
                }
            }
            log.info("Send alarm message ({}, {}) to user ({}, {}).",
                    title, content, userDetail.getUsername(), userDetail.getUserId());
            SendStatus status = mpService.sendAlarmMsg(openId, title, content, new Date());
            if (messageDto != null) {
                eventsService.recordEvents(MAIL_SUBJECT, content, openId, messageDto, status, 0, Type.NOTICE_WECHAT);
            }
        } catch (Exception e) {
            log.error("sendWeChat error: {}", ThrowableUtils.getStackTraceByPn(e));
            return true;
        }
        return true;
    }


    private static  DateField parseDateUnit(DateUnit dateUnit) {
        if (Objects.isNull(dateUnit)) {
            return DateField.MILLISECOND;
        }

        if (dateUnit == DateUnit.MS) {
            return DateField.MILLISECOND;
        } else if (dateUnit == DateUnit.SECOND) {
            return DateField.SECOND;
        } else if (dateUnit == DateUnit.MINUTE) {
            return DateField.MINUTE;
        } else if (dateUnit == DateUnit.HOUR) {
            return DateField.HOUR;
        } else if (dateUnit == DateUnit.WEEK) {
            return DateField.WEEK_OF_MONTH;
        }

        return DateField.MILLISECOND;
    }

    @Override
    public void close(String[] ids, UserDetail userDetail) {
        List<ObjectId> collect = Arrays.stream(ids).map(MongoUtils::toObjectId).collect(Collectors.toList());

        Query query = new Query(Criteria.where("_id").in(collect));
        List<AlarmInfo> alarmInfos = mongoTemplate.find(query, AlarmInfo.class);
        alarmInfos.forEach(info -> {
            String taskId = info.getTaskId();
            String nodeId = info.getNodeId();
            String key = "";
            if (AlarmKeyEnum.TASK_INCREMENT_DELAY.equals(info.getMetric())) {
                key = taskId + "-" + "replicateLag";
            } else if (AlarmKeyEnum.DATANODE_AVERAGE_HANDLE_CONSUME.equals(info.getMetric())) {
                if (info.getSummary().contains("TARGET_")) {
                    key = nodeId + "-targetWriteTimeCostAvg";
                } else {
                    key = nodeId + "-snapshotSourceReadTimeCostAvg";
                }

            } else if (AlarmKeyEnum.PROCESSNODE_AVERAGE_HANDLE_CONSUME.equals(info.getMetric())) {
                key = nodeId + "-timeCostAvg";
            }
            if (StringUtils.isNotBlank(key)) {
                SpringUtil.getBean(MeasureAOP.class).removeObsInfoByTaskIdAndKey(taskId, key);
            }
        });


        Update update = new Update().set("status", AlarmStatusEnum.CLOESE.name())
                .set("closeTime", DateUtil.date())
                .set("closeBy", userDetail.getUserId());
        mongoTemplate.updateMulti(query, update, AlarmInfo.class);
    }

    @Override
    public Page<AlarmListInfoVo> list(String status, Long start, Long end, String keyword, Integer page,
                                      Integer size, UserDetail userDetail, Locale locale) {
        TmPageable pageable = new TmPageable();
        pageable.setPage(page);
        pageable.setSize(size);

        Criteria criteria = new Criteria();
        if (Objects.nonNull(status)) {
            criteria.and("status").is(AlarmStatusEnum.valueOf(status));
        }
        if (Objects.nonNull(keyword)) {
            criteria.orOperator(Criteria.where("name").regex(keyword),
                    Criteria.where("node").regex(keyword),
                    Criteria.where("summary").regex(keyword));
        }

        if (Objects.nonNull(start) && Objects.nonNull(end)) {
            criteria.and("lastOccurrenceTime").gt(DateUtil.date(start)).lt(DateUtil.date(end));
        } else if (Objects.nonNull(start)) {
            criteria.and("lastOccurrenceTime").gt(DateUtil.date(start));
        } else if (Objects.nonNull(end)) {
            criteria.and("lastOccurrenceTime").lt(DateUtil.date(end));
        }

        Query query = new Query(criteria);
        long count = mongoTemplate.count(query, AlarmInfo.class);
        if (count == 0) {
            return new Page<>(count, Lists.newArrayList());
        }

        query.with(Sort.by(Sort.Direction.DESC, "lastOccurrenceTime"));
        List<AlarmInfo> alarmInfos = mongoTemplate.find(query.with(pageable), AlarmInfo.class);

        List<String> taskIds = alarmInfos.stream().map(AlarmInfo::getTaskId).collect(Collectors.toList());

        Map<String, TaskDto> taskDtoMap = taskService.findAllTasksByIds(taskIds).stream().collect(Collectors.toMap(t -> t.getId().toHexString(), Function.identity(), (e1, e2) -> e1));

        ExpressionParser parser = new SpelExpressionParser();
        TemplateParserContext parserContext = new TemplateParserContext();
        List<AlarmListInfoVo> collect = alarmInfos.stream()
                .map(t -> {
                    String template = MessageUtil.getAlarmMsg(locale, t.getSummary());
                    if (Objects.nonNull(t.getParam())) {
                        if (t.getParam().containsValue("GREATER")) {
                            t.getParam().put("flag", MessageUtil.getAlarmMsg(locale, "GREATER"));
                        } else if (t.getParam().containsValue("LESS")){
                            t.getParam().put("flag", MessageUtil.getAlarmMsg(locale, "LESS"));
                        }
                    }

                    String content = parser.parseExpression(template, parserContext).getValue(t.getParam(), String.class);
                    return AlarmListInfoVo.builder()
                            .id(t.getId().toHexString())
                            .level(t.getLevel())
                            .status(t.getStatus())
                            .name(t.getName())
                            .summary(content)
                            .firstOccurrenceTime(t.getFirstOccurrenceTime())
                            .lastOccurrenceTime(t.getLastOccurrenceTime())
                            .lastNotifyTime(t.getLastNotifyTime())
                            .taskId(t.getTaskId())
                            .metric(t.getMetric())
                            .syncType(taskDtoMap.get(t.getTaskId()).getSyncType())
                            .build();
                }).collect(Collectors.toList());

        return new Page<>(count, collect);
    }

    @Override
    public TaskAlarmInfoVo listByTask(AlarmListReqDto dto) {
        String taskId = dto.getTaskId();
        AlarmStatusEnum status = dto.getStatus();
        Level level = dto.getLevel();
        String nodeId = dto.getNodeId();

        Criteria criteria = Criteria.where("taskId").is(taskId);
        if (Objects.nonNull(status)) {
            criteria.and("status").is(status.name());
        } else {
            criteria.and("status").ne(AlarmStatusEnum.CLOESE.name());
        }
        if (Objects.nonNull(level)) {
            criteria.and("level").is(level.name());
        }
        if (Objects.nonNull(nodeId)) {
            criteria.and("nodeId").is(nodeId);
        }
        Query query = new Query(criteria);
        query.with(Sort.by(Sort.Direction.DESC, "lastOccurrenceTime"));
        List<AlarmInfo> alarmInfos = mongoTemplate.find(query, AlarmInfo.class);

        Map<String, Integer> nodeNumMap = Maps.newHashMap();
        List<AlarmListInfoVo> collect = Lists.newArrayList();
        ExpressionParser parser = new SpelExpressionParser();
        TemplateParserContext parserContext = new TemplateParserContext();
        alarmInfos.forEach(t -> {
            String template = MessageUtil.getAlarmMsg(dto.getLocale(), t.getSummary());
            if (Objects.nonNull(t.getParam())) {
                if (t.getParam().containsValue("GREATER")) {
                    t.getParam().put("flag", MessageUtil.getAlarmMsg(dto.getLocale(), "GREATER"));
                } else if (t.getParam().containsValue("LESS")){
                    t.getParam().put("flag", MessageUtil.getAlarmMsg(dto.getLocale(), "LESS"));
                }
            }

            String content = parser.parseExpression(template, parserContext).getValue(t.getParam(), String.class);
            AlarmListInfoVo build = AlarmListInfoVo.builder()
                    .id(t.getId().toHexString())
                    .level(t.getLevel())
                    .status(t.getStatus())
                    .name(t.getName())
                    .summary(content)
                    .firstOccurrenceTime(t.getFirstOccurrenceTime())
                    .lastOccurrenceTime(t.getLastOccurrenceTime())
                    .lastNotifyTime(t.getLastNotifyTime())
                    .taskId(t.getTaskId())
                    .metric(t.getMetric())
                    .nodeId(t.getNodeId())
                    .build();

            collect.add(build);

            String nId = t.getNodeId();
            if (Objects.nonNull(nId)) {
                if (nodeNumMap.containsKey(nId)) {
                    nodeNumMap.put(nId, nodeNumMap.get(nId) + 1);
                } else {
                    nodeNumMap.put(nId, 1);
                }
            }
        });

        List<TaskAlarmNodeInfoVo> taskAlarmNodeInfoVos = Optional.ofNullable(taskService.findById(MongoUtils.toObjectId(taskId))).map(taskDto -> taskDto.getDag().getNodes().stream().map(t ->
                TaskAlarmNodeInfoVo.builder()
                        .nodeId(t.getId())
                        .nodeName(t.getName())
                        .num(nodeNumMap.get(t.getId()))
                        .build()
        ).collect(Collectors.toList())).orElse(new ArrayList<>());

        long alert = alarmInfos.stream().filter(t -> !AlarmStatusEnum.CLOESE.equals(t.getStatus())
                && Lists.of(Level.WARNING).contains(t.getLevel())).count();
        long error = alarmInfos.stream().filter(t -> !AlarmStatusEnum.CLOESE.equals(t.getStatus())
                && Lists.of(Level.CRITICAL, Level.EMERGENCY).contains(t.getLevel())).count();

        return TaskAlarmInfoVo.builder()
                .nodeInfos(taskAlarmNodeInfoVos)
                .alarmList(collect)
                .alarmNum(new AlarmNumVo(alert, error))
                .build();
    }

    @Override
    public List<AlarmInfo> find(String taskId, String nodeId, AlarmKeyEnum key) {
        Criteria criteria = Criteria.where("taskId").is(taskId)
                .and("metric").is(key)
                .and("status").ne(AlarmStatusEnum.CLOESE)
                .and("is_deleted").ne(true);
        if (Objects.nonNull(nodeId)) {
            criteria.and("nodeId").is(nodeId);
        }

        Query query = new Query(criteria);
        return mongoTemplate.find(query, AlarmInfo.class);
    }

    @Override
    public void closeWhenTaskRunning(String taskId) {
        Update update = Update.update("status", AlarmStatusEnum.CLOESE);
        mongoTemplate.updateMulti(Query.query(Criteria.where("taskId").is(taskId)), update, AlarmInfo.class);
    }

    @Override
    public MailAccountDto getMailAccount(String userId) {
        List<Settings> all = settingsService.findAll();
        Map<String, Object> collect = all.stream().collect(Collectors.toMap(Settings::getKey, Settings::getValue, (e1, e2) -> e1));

        String host = (String) collect.get("smtp.server.host");
        String port = (String) collect.getOrDefault("smtp.server.port", "465");
        String from = (String) collect.get("email.send.address");
        String user = (String) collect.get("smtp.server.user");
        Object pwd = collect.get("smtp.server.password");
        String password = Objects.nonNull(pwd) ? pwd.toString() : null;
        String protocol = (String) collect.get("email.server.tls");

        AtomicReference<List<String>> receiverList = new AtomicReference<>();

        boolean isCloud = settingsService.isCloud();
        if (isCloud) {
            UserDetail userDetail = userService.loadUserById(MongoUtils.toObjectId(userId));
            Optional.ofNullable(userDetail).ifPresent(u -> {
                if (StringUtils.isNotBlank(u.getEmail())) {
                    receiverList.set(Lists.newArrayList(u.getEmail()));
                }
            });
        } else {
            String receivers = (String) collect.get("email.receivers");
            if (StringUtils.isNotBlank(receivers)) {
                String[] split = receivers.split(",");
                receiverList.set(Arrays.asList(split));
            }
        }

        return MailAccountDto.builder().host(host).port(Integer.valueOf(port)).from(from).user(user).pass(password)
                .receivers(receiverList.get()).protocol(protocol).build();
    }

    public boolean enableEmail() {
        List<Settings> result = settingsService.findAll(Query.query(Criteria.where("category").is("SMTP")
                .and("key").in("smtp.server.host", "smtp.server.port", "smtp.server.user", "smtp.server.password", "email.send.address")));
        return result != null && result.stream().map(Settings::getValue).filter(Objects::nonNull).map(Object::toString)
                .map(StringUtils::isNotBlank).reduce(false, (v1, v2) -> v1 || v2);
    }

    @Override
    public void delAlarm(String taskId) {
        mongoTemplate.remove(Query.query(Criteria.where("taskId").is(taskId)), AlarmInfo.class);
    }

    @Override
    public List<AlarmInfo> query(Query query) {
        return mongoTemplate.find(query, AlarmInfo.class);
    }

    /**
     * 只有三种情况会新增一条message
     * 1、agent启动。TCM触发，TCM通过接口创建消息。
     * 2、agent停止。TCM触发，TCM通过接口创建消息。
     * 3、任务出错。通过dataFlows数据中status属性改为error，做为判断依据，创建消息通知。  这个时候需要发送邮件，或者短信通知用户
     * @param messageDto
     * @return
     */
    public MessageDto add(MessageDto messageDto, UserDetail userDetail) {
        try {
            log.info("informUser");
            sendMessage(null, null, userDetail, messageDto);
            sendMail(null, null, userDetail, messageDto);
            sendSms(null, null, userDetail, messageDto);
            sendWeChat(null, null, userDetail, messageDto);
        } catch (Exception e) {
            log.error("新增消息异常，", e);
        }
        return messageDto;
    }

    public static void main(String[] args) {
        DateTime lastNotifyTime = DateUtil.offset(new Date(), parseDateUnit(DateUnit.SECOND), 600);
        System.out.println(lastNotifyTime);
    }

    @Override
    public List<AlarmChannelDto> getAvailableChannels() {
        List<AlarmChannelDto> availableChannels = new ArrayList<>();
        availableChannels.add(new AlarmChannelDto("system"));
        if (smsService.enableSms() && enableSms) {
            availableChannels.add(new AlarmChannelDto(smsService.getType()));
        }
        if (mpService.enableWeChat()) {
            availableChannels.add(new AlarmChannelDto(mpService.getType()));
        }
        if (enableEmail()) {
            availableChannels.add(new AlarmChannelDto("email"));
        }
        return availableChannels;
    }
}
