package dev.rabauer.ai.diary.views.today;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TodayGreetingTest {

    private static LocalDateTime at(int hour, int minute) {
        return LocalDateTime.of(2026, 11, 24, hour, minute);
    }

    @Test
    void greetingFollowsTheTimeOfDay() {
        assertEquals("today.greeting.morning", TodayGreeting.key(at(0, 0)));
        assertEquals("today.greeting.morning", TodayGreeting.key(at(11, 59)));
        assertEquals("today.greeting.afternoon", TodayGreeting.key(at(12, 0)));
        assertEquals("today.greeting.afternoon", TodayGreeting.key(at(17, 59)));
        assertEquals("today.greeting.evening", TodayGreeting.key(at(18, 0)));
        assertEquals("today.greeting.evening", TodayGreeting.key(at(23, 59)));
    }

    @Test
    void dateLineIsLocaleAware() {
        String en = TodayGreeting.dateLine(at(9, 0), Locale.ENGLISH);
        String de = TodayGreeting.dateLine(at(9, 0), Locale.GERMAN);
        assertTrue(en.contains("Tuesday") && en.contains("24") && en.contains("November"), en);
        assertTrue(de.contains("Dienstag") && de.contains("24") && de.contains("November"), de);
    }
}
