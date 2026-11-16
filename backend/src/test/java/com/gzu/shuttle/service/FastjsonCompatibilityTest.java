package com.gzu.shuttle.service;

import static org.junit.jupiter.api.Assertions.*;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

class FastjsonCompatibilityTest {
    @Test
    void keepsExistingJsonParseAndSerializeContract() {
        JSONObject parsed = JSON.parseObject("{\"type\":\"queue_update\",\"waitingCount\":7}");
        assertEquals("queue_update", parsed.getString("type"));
        assertEquals(7, parsed.getIntValue("waitingCount"));
        String serialized = JSON.toJSONString(parsed);
        assertEquals(7, JSON.parseObject(serialized).getIntValue("waitingCount"));
    }
}
