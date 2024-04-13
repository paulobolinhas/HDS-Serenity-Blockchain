package pt.ulisboa.tecnico.hdsledger.utilities.test;

public enum TestScenario {
    PRODUCTION,

    UNAUTHORIZED_CLIENT,

    MALFORMED_CLIENT_SIGNATURE,

    UNAUTHORIZED_NODE,

    MALFORMED_NODE_SIGNATURE,

    LEADER_TIMEOUT, // should result in a round change

    DETECT_REPLAY_ATTACK,

    ROUND_CHANGE_AFTER_PREPARE
}
