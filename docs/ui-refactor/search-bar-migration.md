# Backend-native search bars

The Store and application selector call mixui SearchBar directly. MIUIX uses its native SearchBar/InputField; Mochi uses Maven 0.2.0 MochiSearchBar. The app-specific RearSearchBar, acrylic implementation, style enum, configuration entry, preference key constant and three translated style labels are removed.

Existing persisted module_search_bar_style values are no longer read or written. They cannot select the removed control; no destructive preferences rewrite is necessary. Other preferences and the query/filter/IME/focus callbacks are unchanged.

Local regression covers both native backends in light/dark appearance: repeated input, clear, submit, focus release, cancel and re-entry, plus the real Store search/sort/navigation test. Screenshots paint the actual themed page background, not the default activity window behind translucent controls. Keyboard hide requests are observed through the controller local; this is not physical-device IME evidence.

Native-only validation passed: local app/mixui compilation and four focused methods (two search contracts, the actual Store root and the removed-setting assertion), in the six-method combined run.
