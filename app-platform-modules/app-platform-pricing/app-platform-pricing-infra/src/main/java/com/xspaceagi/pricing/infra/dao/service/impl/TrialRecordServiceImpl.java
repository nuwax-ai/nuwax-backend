package com.xspaceagi.pricing.infra.dao.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xspaceagi.pricing.infra.dao.entity.TrialRecord;
import com.xspaceagi.pricing.infra.dao.mapper.TrialRecordMapper;
import com.xspaceagi.pricing.infra.dao.service.ITrialRecordService;
import org.springframework.stereotype.Service;

@Service
public class TrialRecordServiceImpl extends ServiceImpl<TrialRecordMapper, TrialRecord> implements ITrialRecordService {
}
