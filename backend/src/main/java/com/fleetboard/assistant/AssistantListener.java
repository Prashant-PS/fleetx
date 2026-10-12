package com.fleetboard.assistant;

import com.fleetboard.search.Answer;

import java.util.List;

/** Receives a streamed assistant reply. */
public interface AssistantListener {

    void status(String message);

    void text(String delta);

    void action(ProposedAction action);

    void sources(List<Answer.Source> sources);
}
