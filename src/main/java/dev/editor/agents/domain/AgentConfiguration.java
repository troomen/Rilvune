package dev.editor.agents.domain;
import java.util.*;

/** All advertised select options survive translation. Model and effort have convenient typed projections. */
public record AgentConfiguration(List<AgentModel> models, String selectedModel, List<AgentEffort> efforts, String selectedEffort,
                                 List<AgentConfigOption> options) {
    public AgentConfiguration { models = List.copyOf(models); efforts = List.copyOf(efforts); options = List.copyOf(options); }
    public AgentConfiguration(List<AgentModel> models, String selectedModel, List<AgentEffort> efforts, String selectedEffort) {
        this(models, selectedModel, efforts, selectedEffort, List.of());
    }
    public static AgentConfiguration empty() { return new AgentConfiguration(List.of(), "", List.of(), "", List.of()); }
    public static AgentConfiguration from(List<AgentConfigOption> options, String modelPrefix, String recommended) {
        AgentConfigOption model = options.stream().filter(option -> option.category() == AgentConfigOption.Category.MODEL).findFirst().orElse(null);
        List<AgentModel> models = model == null ? List.of() : model.choices().stream().filter(choice -> choice.id().startsWith(modelPrefix))
                .map(choice -> new AgentModel(choice.id(), choice.name().startsWith(modelPrefix) ? choice.name().substring(modelPrefix.length()) : choice.name()))
                .distinct().sorted(Comparator.comparing(AgentModel::name)).toList();
        String selected = model != null && models.stream().anyMatch(item -> item.id().equals(model.value())) ? model.value()
                : models.stream().anyMatch(item -> item.id().equals(recommended)) ? recommended : models.isEmpty() ? "" : models.getFirst().id();
        AgentConfigOption effort = options.stream().filter(option -> option.category() == AgentConfigOption.Category.EFFORT).findFirst().orElse(null);
        return new AgentConfiguration(models, selected, effort == null ? List.of() : effort.choices().stream().map(choice -> new AgentEffort(choice.id(), choice.name())).toList(),
                effort == null ? "" : effort.value(), options);
    }
}
