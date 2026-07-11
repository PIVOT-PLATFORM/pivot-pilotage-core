package fr.pivot.pilotage.calendar;

import fr.pivot.pilotage.schedule.CalendarScope;
import fr.pivot.pilotage.testsupport.PlatformSchemaTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVC tests for {@link CalendarController} (US22.4.5) — full Spring context against a real
 * PostgreSQL 18 (Testcontainers, only to boot the context/Flyway), with {@link CalendarService} and
 * {@link CalendarEditPolicy} mocked via {@code @MockitoBean}. Mirrors {@code WbsTaskControllerIT}.
 * Covers the HTTP contract: the 403 write gate, the 404 non-disclosure, the 422 validation body, and
 * the 200/201 delegation. Row-level behaviour is {@link CalendarServiceIT}'s job.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class CalendarControllerIT {

    private static final long TENANT = 42L;
    private static final long TEAM = 5L;
    private static final long CAL = 100L;
    private static final long PROJECT = 7L;
    private static final long TASK = 9L;
    private static final String BASE = "/tenants/" + TENANT + "/teams/" + TEAM;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18");

    /**
     * Registers the container datasource and seeds {@code public} before Spring/Flyway.
     *
     * @param registry the dynamic property registry
     * @throws Exception if seeding the public schema fails
     */
    @DynamicPropertySource
    static void overrideProperties(final DynamicPropertyRegistry registry) throws Exception {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        PlatformSchemaTestSupport.createPublicSchema(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @Autowired
    private WebApplicationContext wac;

    @MockitoBean
    private CalendarService calendarService;

    @MockitoBean
    private CalendarEditPolicy editPolicy;

    private MockMvc mockMvc;

    /** Builds MockMvc from the web application context before each test. */
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
    }

    private static String createBody() {
        return "{\"scope\":\"PROJECT\",\"projectId\":" + PROJECT + ",\"name\":\"Std\","
                + "\"workingDays\":[1,2,3,4,5],\"ranges\":[{\"startHour\":9,\"endHour\":17}]}";
    }

    // -------- Security AC: read-only role → 403 on writes, service never touched ------------------

    @Test
    void createCalendar_denied_returns403_andServiceNotCalled() throws Exception {
        when(editPolicy.isAuthorized()).thenReturn(false);

        mockMvc.perform(post(BASE + "/calendars")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody()))
                .andExpect(status().isForbidden());

        verify(calendarService, never()).create(anyLong(), anyLong(), any());
    }

    @Test
    void addException_denied_returns403() throws Exception {
        when(editPolicy.isAuthorized()).thenReturn(false);

        mockMvc.perform(post(BASE + "/calendars/" + CAL + "/exceptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startDate\":\"2026-05-01\",\"endDate\":\"2026-05-01\",\"working\":false}"))
                .andExpect(status().isForbidden());

        verify(calendarService, never()).addException(anyLong(), anyLong(), anyLong(), any());
    }

    // -------- AC: authorized create → 201, delegates to the service ------------------------------

    @Test
    void createCalendar_authorized_returns201() throws Exception {
        when(editPolicy.isAuthorized()).thenReturn(true);
        when(calendarService.create(eq(TENANT), eq(TEAM), any())).thenReturn(new CalendarResponse(
                CAL, PROJECT, CalendarScope.PROJECT, "Std", List.of(1, 2, 3, 4, 5),
                List.of(new WorkingTimeRange(9, 17))));

        mockMvc.perform(post(BASE + "/calendars")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.calendarId").value((int) CAL))
                .andExpect(jsonPath("$.scope").value("PROJECT"));

        verify(calendarService).create(eq(TENANT), eq(TEAM), any());
    }

    // -------- AC: list is a read, not gated ------------------------------------------------------

    @Test
    void listCalendars_read_returns200() throws Exception {
        when(calendarService.list(TENANT, TEAM)).thenReturn(List.of());

        mockMvc.perform(get(BASE + "/calendars")).andExpect(status().isOk());
    }

    // -------- Security AC: cross-tenant read → 404 non-disclosure ---------------------------------

    @Test
    void readCalendar_crossTenant_returns404() throws Exception {
        when(calendarService.read(TENANT, TEAM, CAL))
                .thenThrow(new CalendarNotFoundException(CAL, TENANT, TEAM));

        mockMvc.perform(get(BASE + "/calendars/" + CAL)).andExpect(status().isNotFound());
    }

    // -------- Error AC: end before start → 422 with explicit body --------------------------------

    @Test
    void addException_endBeforeStart_returns422() throws Exception {
        when(editPolicy.isAuthorized()).thenReturn(true);
        when(calendarService.addException(anyLong(), anyLong(), anyLong(), any()))
                .thenThrow(InvalidCalendarException.endBeforeStart("2026-05-05", "2026-05-01"));

        mockMvc.perform(post(BASE + "/calendars/" + CAL + "/exceptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startDate\":\"2026-05-05\",\"endDate\":\"2026-05-01\",\"working\":false}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(InvalidCalendarException.CODE));
    }

    // -------- AC: effective-calendar resolution is a read, delegates ------------------------------

    @Test
    void effectiveCalendar_read_returns200_withResolvedFrom() throws Exception {
        when(calendarService.resolveEffective(TENANT, TEAM, PROJECT, TASK, "alice"))
                .thenReturn(new EffectiveCalendarResponse(CAL, CalendarScope.RESOURCE,
                        new CalendarResponse(CAL, null, CalendarScope.RESOURCE, "alice",
                                List.of(1, 2, 3, 4, 5), List.of(new WorkingTimeRange(9, 17)))));

        mockMvc.perform(get(BASE + "/projects/" + PROJECT + "/tasks/" + TASK + "/effective-calendar")
                        .param("resourceRef", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolvedFrom").value("RESOURCE"))
                .andExpect(jsonPath("$.calendarId").value((int) CAL));
    }

    // -------- AC: authorized update → 200, delegates ---------------------------------------------

    @Test
    void updateCalendar_authorized_returns200() throws Exception {
        when(editPolicy.isAuthorized()).thenReturn(true);
        when(calendarService.update(eq(TENANT), eq(TEAM), eq(CAL), any())).thenReturn(new CalendarResponse(
                CAL, PROJECT, CalendarScope.PROJECT, "New", List.of(1, 2, 3),
                List.of(new WorkingTimeRange(8, 12))));

        mockMvc.perform(put(BASE + "/calendars/" + CAL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"New\",\"workingDays\":[1,2,3],"
                                + "\"ranges\":[{\"startHour\":8,\"endHour\":12}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("New"));

        verify(calendarService).update(eq(TENANT), eq(TEAM), eq(CAL), any());
    }

    // -------- AC: list/remove exceptions (read not gated; remove gated) ---------------------------

    @Test
    void listExceptions_read_returns200() throws Exception {
        when(calendarService.listExceptions(TENANT, TEAM, CAL)).thenReturn(List.of());

        mockMvc.perform(get(BASE + "/calendars/" + CAL + "/exceptions")).andExpect(status().isOk());
    }

    @Test
    void removeException_authorized_returns204() throws Exception {
        when(editPolicy.isAuthorized()).thenReturn(true);

        mockMvc.perform(delete(BASE + "/calendars/" + CAL + "/exceptions/3"))
                .andExpect(status().isNoContent());

        verify(calendarService).removeException(TENANT, TEAM, CAL, 3L);
    }

    // -------- AC: authorized delete → 204 --------------------------------------------------------

    @Test
    void deleteCalendar_authorized_returns204() throws Exception {
        when(editPolicy.isAuthorized()).thenReturn(true);

        mockMvc.perform(delete(BASE + "/calendars/" + CAL)).andExpect(status().isNoContent());

        verify(calendarService).delete(TENANT, TEAM, CAL);
    }
}
