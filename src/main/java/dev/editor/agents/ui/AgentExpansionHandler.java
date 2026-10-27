package dev.editor.agents.ui;

import javax.swing.JComponent;

/** The scroll owner captures reading intent before a disclosure changes its layout. */
@FunctionalInterface
interface AgentExpansionHandler {
    AgentExpansionHandler DIRECT = (target, change) -> change.run();
    void change(JComponent target, Runnable change);
}
