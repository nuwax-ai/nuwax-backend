package com.xspaceagi.pay.domain.model;

import java.util.List;

public record PayDeveloperAccountPageSlice(List<PayDeveloperAccountModel> records, long total) {}
