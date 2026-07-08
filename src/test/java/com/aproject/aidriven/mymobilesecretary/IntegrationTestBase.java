package com.aproject.aidriven.mymobilesecretary;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 整合測試基底:真實 PostGIS + Redis(Testcontainers)+ MockMvc。
 *
 * 關鍵規則:所有整合測試都繼承這個類,確保 context 設定完全相同,
 * Spring 才能快取同一個 ApplicationContext,容器只啟動一次。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    @Autowired
    protected MockMvc mockMvc;
}
