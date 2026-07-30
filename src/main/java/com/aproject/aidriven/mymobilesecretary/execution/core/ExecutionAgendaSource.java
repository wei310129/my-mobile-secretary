package com.aproject.aidriven.mymobilesecretary.execution.core;

@FunctionalInterface
public interface ExecutionAgendaSource {

    ExecutionAgendaSnapshot load();
}
