package com.xspaceagi.agent.core.adapter.dto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GenerateInfoAiDto implements Serializable {

    @JsonPropertyDescription("项目名称，简洁有意义，不超过20个字符")
    private String name;

    @JsonPropertyDescription("项目描述，一句话概括功能和用途，不超过100个字符")
    private String description;

    @JsonPropertyDescription("SVG icon that visually represents the topic, viewBox='0 0 100 100', width='120' height='120'. Return empty for meaningless contexts (input errors, gibberish, arbitrary content); never output an icon unrelated to the topic.\nDesign rules:\n- Concept: distill the topic into ONE clear graphic metaphor, centered with generous negative space.\n- Color: derive the palette from the topic's MEANING (semantic color), not arbitrarily. Mapping: trust/tech/communication -> blue; growth/success/health -> green or teal; energy/warmth/creativity -> orange or yellow; premium/imagination -> purple; urgency/passion -> red; neutral/minimal -> gray. Use a modern solid fill or a subtle same-hue gradient; at most 3 colors; the subject must contrast with the background (foreground defaults to white or a contrasting color).\n- Container: a square background (rounded corners optional); avoid a circular badge unless it suits the concept.\n- Geometry: simple geometric shapes and clean lines (if strokes are used, keep them bold, about 3-6 on the 100 grid); no embedded text and no external references; shapes must be bold enough to stay recognizable when displayed small (e.g. 24px).\n- Validity: a valid, self-contained <svg> string that renders standalone; include role='img' and aria-label='<topic>' for accessibility; clean, balanced, professional.")
    private String svgIcon;
}
