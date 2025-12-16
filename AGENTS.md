# Agent Guidelines

This document contains guidelines for AI agents working on this codebase.

## Clojure Docstring Placement

**IMPORTANT**: In Clojure, docstrings go **BEFORE** the parameter list, not after.

### Correct Format:
```clojure
(defn my-function
  "This is the docstring - it comes BEFORE the parameter list."
  [param1 param2]
  (function-body))
```

### Incorrect Format:
```clojure
(defn my-function
  [param1 param2]
  "This is WRONG - docstring should not be here"
  (function-body))
```

### Why This Matters:
- Clojure's `defn` macro expects docstrings before the parameter list
- Placing docstrings after parameters causes linter warnings
- It's a common mistake when coming from other languages (like Python)

### When Adding/Modifying Functions:
- Always place docstrings before `[parameters]`
- Use multi-line strings for longer docstrings
- Keep docstrings descriptive and clear

