package com.aproject.aidriven.mymobilesecretary.account.persistence;

import com.aproject.aidriven.mymobilesecretary.account.domain.AppUser;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {
}
