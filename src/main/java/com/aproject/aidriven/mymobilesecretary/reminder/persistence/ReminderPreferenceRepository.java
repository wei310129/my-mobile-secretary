package com.aproject.aidriven.mymobilesecretary.reminder.persistence;

import com.aproject.aidriven.mymobilesecretary.reminder.domain.ReminderPreference;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReminderPreferenceRepository extends JpaRepository<ReminderPreference, Integer> {

    Optional<ReminderPreference> findFirstByOrderByIdAsc();
}
