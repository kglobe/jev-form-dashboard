package jev.dashboard.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jev.dashboard.TestData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/** 唯讀分享 spec 2.2：登入時查帳號的資料庫失敗是 500「伺服器錯誤」，不是「帳號或密碼錯誤」。另開一個 context。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LoginDatabaseFailureTest {
    @Autowired MockMvc mvc;
    @MockitoSpyBean AccountRepository accounts;

    @Test
    void aDatabaseErrorDuringLoginIs500() throws Exception {
        doThrow(new DataAccessResourceFailureException("down")).when(accounts).find(any());
        mvc.perform(formLogin("/api/auth/login").user("u1").password(TestData.PASSWORD))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error").value("伺服器錯誤"));
    }
}
