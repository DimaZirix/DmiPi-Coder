# UI Wireframes

Low-fidelity, **style-independent** schematics of each console screen. They show which elements exist and where they sit, not colours, fonts or final styling. The console is a terminal: every "screen" is a state of the one session view.

| Screen | File | Shows |
|---|---|---|
| Session view | [1-session.svg](1-session.svg) | Start banner with saved sessions, the prompt line, activity lines with their results (text, diff, task list), the subagent prefix, the streamed answer, the compaction note. |
| Permission question | [2a-permission-question.svg](2a-permission-question.svg) | A question with a diff preview and the three permission options; the shape rule for an option list; re-asking on invalid input. |
| Plan approval | [2b-plan-approval.svg](2b-plan-approval.svg) | Plan mode: a blocked edit, the agent presenting its plan as the preview, approval switching the mode back. |
| Checkbox question | [2c-checkbox-question.svg](2c-checkbox-question.svg) | The checkbox shape: option details, comma-separated selection. Supported shape; no built-in plugin asks one today. |

These pair with the [Functional Overview](../FUNCTIONAL-OVERVIEW.md): the session view is §1, the three question screens are §2. Placement only, not the visual design.
