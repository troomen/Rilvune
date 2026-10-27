package dev.editor.agents.ui;

import dev.editor.agents.domain.*;
import dev.editor.platform.theme.*;
import javax.swing.*;
import java.awt.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.List;
import java.util.function.*;

/** Explicit form response, independent of chat submission and permission approval. Streaming never rebuilds an edited form. */
final class AgentQuestionView extends JPanel {
    private final BiConsumer<String, AgentQuestionAnswer> answer;
    private final AgentUi.Column fields = new AgentUi.Column();
    private final JScrollPane scroll;
    private final JPanel actions = AgentUi.row();
    private final JTextArea heading = AgentUi.text("agents.question.title"), error = AgentUi.text("agents.question.error");
    private final Map<String, Supplier<Object>> values = new LinkedHashMap<>();
    private final JButton reply, decline;
    private AgentQuestion displayed;
    private EditorTheme theme;
    AgentQuestionView(EditorTheme theme, BiConsumer<String, AgentQuestionAnswer> answer) {
        super(new BorderLayout(0, 6)); this.theme = theme; this.answer = answer; setName("agents.questions"); setVisible(false);
        fields.setOpaque(false);
        AgentUi.Column content = new AgentUi.Column(); content.setOpaque(false);
        heading.setAlignmentX(LEFT_ALIGNMENT); fields.setAlignmentX(LEFT_ALIGNMENT);
        content.add(heading); content.add(Box.createVerticalStrut(8)); content.add(fields);
        error.setVisible(false); scroll = AgentContentView.scroll(content, 180); scroll.getViewport().setOpaque(false); add(scroll);
        scroll.setMinimumSize(new Dimension(0, 90));
        JPanel footer = new JPanel(new BorderLayout(0, 4)); footer.setOpaque(false); footer.add(error, BorderLayout.NORTH); footer.add(actions); add(footer, BorderLayout.SOUTH);
        reply = AgentUi.button("Reply", "agents.question.reply", () -> respond(AgentQuestionAnswer.Action.ACCEPT));
        decline = AgentUi.quietButton("Skip question", "agents.question.decline", () -> respond(AgentQuestionAnswer.Action.DECLINE)); actions.add(reply); actions.add(decline); applyTheme(theme);
    }
    void render(List<AgentQuestion> questions, boolean enabled) {
        setVisible(!questions.isEmpty()); if (questions.isEmpty()) { displayed = null; return; }
        AgentQuestion next = questions.getFirst(); reply.setEnabled(enabled); decline.setEnabled(enabled);
        if (next.equals(displayed)) return; displayed = next; values.clear(); fields.removeAll(); error.setText(""); error.setVisible(false);
        heading.setText(next.title());
        for (AgentQuestion.Field field : next.fields()) {
            JLabel title = AgentUi.label(field.title() + (field.required() ? " *" : "")); title.setAlignmentX(LEFT_ALIGNMENT); fields.add(title);
            JComponent input = input(field); input.setName("agents.question.field." + field.key()); input.getAccessibleContext().setAccessibleName(field.title());
            input.setAlignmentX(LEFT_ALIGNMENT); input.setMaximumSize(new Dimension(Integer.MAX_VALUE, input.getPreferredSize().height)); fields.add(input);
            String hint = field.description() + (field.format().isBlank() ? "" : " · " + field.format()) + (field.pattern().isBlank() ? "" : " · " + field.pattern());
            if (!hint.isBlank()) { JTextArea description = AgentUi.text("agents.question.description"); description.setText(hint); description.setAlignmentX(LEFT_ALIGNMENT); fields.add(description); }
            fields.add(Box.createVerticalStrut(8));
        }
        applyTheme(theme); scroll.getVerticalScrollBar().setValue(0); fields.revalidate(); revalidate();
    }
    private JComponent input(AgentQuestion.Field field) {
        if (field.type() == AgentQuestion.Type.BOOLEAN) {
            JCheckBox check = new JCheckBox("Yes", Boolean.TRUE.equals(field.initial())); values.put(field.key(), check::isSelected); return check;
        }
        if (field.type() == AgentQuestion.Type.CHOICE) {
            JComboBox<AgentQuestion.Choice> choices = new JComboBox<>(field.choices().toArray(AgentQuestion.Choice[]::new)); choices.setSelectedItem(null);
            choices.setRenderer(new DefaultListCellRenderer() { { putClientProperty("html.disable", true); } @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) { return super.getListCellRendererComponent(list, value == null ? "Choose…" : value, index, selected, focus); } });
            field.choices().stream().filter(choice -> choice.value().equals(field.initial())).findFirst().ifPresent(choices::setSelectedItem);
            values.put(field.key(), () -> choices.getSelectedItem() instanceof AgentQuestion.Choice choice ? choice.value() : null); return choices;
        }
        if (field.type() == AgentQuestion.Type.MULTIPLE) {
            JList<AgentQuestion.Choice> choices = new JList<>(field.choices().toArray(AgentQuestion.Choice[]::new)); choices.setCellRenderer(new DefaultListCellRenderer() { { putClientProperty("html.disable", true); } }); choices.setVisibleRowCount(Math.min(4, Math.max(1, field.choices().size()))); choices.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
            if (field.initial() instanceof List<?> initial) for (int index = 0; index < field.choices().size(); index++) if (initial.contains(field.choices().get(index).value())) choices.addSelectionInterval(index, index);
            values.put(field.key(), () -> { var selected = choices.getSelectedValuesList().stream().map(AgentQuestion.Choice::value).toList(); return selected.isEmpty() && !field.required() ? null : selected; }); return new JScrollPane(choices);
        }
        JTextField input = new JTextField(field.initial() == null ? "" : field.initial().toString(), 20); input.putClientProperty("JTextField.placeholderText", field.type() == AgentQuestion.Type.TEXT ? "Your answer" : "Number");
        values.put(field.key(), () -> {
            String text = input.getText(); if (text.isEmpty() && !field.required()) return null;
            if (field.type() == AgentQuestion.Type.TEXT) return text;
            try { return new BigDecimal(text.strip()); } catch (NumberFormatException invalid) { throw new IllegalArgumentException(field.title() + " must be a number"); }
        }); return input;
    }
    private void respond(AgentQuestionAnswer.Action action) {
        if (displayed == null) return;
        try {
            Map<String, Object> result = new LinkedHashMap<>();
            if (action == AgentQuestionAnswer.Action.ACCEPT) {
                values.forEach((key, read) -> { Object value = read.get(); if (value != null) result.put(key, value); }); displayed.validate(result);
            }
            answer.accept(displayed.id(), new AgentQuestionAnswer(action, result));
        } catch (IllegalArgumentException invalid) { error.setText(invalid.getMessage()); error.setVisible(true); revalidate(); }
    }
    void applyTheme(EditorTheme theme) { this.theme = theme; setOpaque(false); UiTypography.apply(this, theme); heading.setForeground(theme.text()); error.setForeground(AgentUi.errorColor(theme));
        scroll.setBackground(theme.raised()); scroll.getViewport().setOpaque(true); scroll.getViewport().setBackground(theme.raised());
        for (Component input : fields.getComponents()) if (input instanceof JComponent control && control.getName() != null && control.getName().startsWith("agents.question.field.")) control.setMaximumSize(new Dimension(Integer.MAX_VALUE, control.getPreferredSize().height)); }
}
