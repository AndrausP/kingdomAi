package com.kingdomsai.core.npc;

public enum NpcActivity {
    IDLE("à toa"),
    WORK("trabalhando"),
    BUILD("construindo"),
    PATROL("patrulhando"),
    GUARD("de guarda"),
    SOCIALIZE("conversando na praça"),
    GO_HOME("indo para casa"),
    SLEEP("dormindo"),
    TALKING("falando com o rei"),
    SUMMONED("atendendo ao chamado do rei"),
    MARCH("em campanha"),
    IMPRISONED("preso");

    public final String display;

    NpcActivity(String display) {
        this.display = display;
    }
}
