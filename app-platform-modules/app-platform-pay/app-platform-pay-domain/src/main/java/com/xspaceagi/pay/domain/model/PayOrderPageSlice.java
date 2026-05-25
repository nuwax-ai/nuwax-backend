package com.xspaceagi.pay.domain.model;

import java.util.List;

public record PayOrderPageSlice(List<PayOrderModel> records, long total) {}
