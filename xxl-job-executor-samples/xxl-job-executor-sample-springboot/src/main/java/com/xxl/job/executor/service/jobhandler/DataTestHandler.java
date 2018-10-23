package com.xxl.job.executor.service.jobhandler;

import com.xxl.job.core.biz.model.ReturnT;
import com.xxl.job.core.handler.IJobHandler;
import com.xxl.job.core.handler.annotation.JobHandler;
import com.xxl.job.core.log.XxlJobLogger;
import com.xxl.job.executor.dao.TestTableMapper;
import com.xxl.job.executor.model.TestTable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@JobHandler(value = "dataTestHandler")
@Component
public class DataTestHandler extends IJobHandler {


    @Autowired
    TestTableMapper mapper;


    @Override
    public ReturnT<String> execute(String param) throws Exception {
        TestTable testTable = new TestTable();
//        testTable.setId();
        testTable.setAge(11);
        testTable.setName("nihao");
        XxlJobLogger.log("插入成功");
        mapper.insert(testTable);
        return SUCCESS;
    }
}
