# Third-party files in this repository

| Path | Project | Licence |
|---|---|---|
| `public/js/pdf.min.mjs`, `public/js/pdf.worker.min.mjs` | [pdf.js](https://github.com/mozilla/pdf.js) 6.5.438, Mozilla Foundation | Apache-2.0 (notice in file header) |
| `public/css/driver.css` | [driver.js](https://github.com/kamranahmedse/driver.js) 1.14.0, Kamran Ahmed | MIT |
| some icon paths in `src/loretile/icons.cljs` (e.g. `:link`, `:pin`) | [Lucide](https://github.com/lucide-icons/lucide) / [Feather](https://github.com/feathericons/feather) | ISC (Lucide) / MIT (Feather) — texts below |
| `test/data/208topics.csv`, `test/data/prerequisite_annotation.csv` | [LectureBank](https://github.com/Yale-LILY/LectureBank) (`LB-Paper/`), Yale LILY Lab | No licence stated upstream; included unmodified for the prerequisite benchmark (`test/edge-bench.js`). Cite: Li et al., *What Should I Learn First: Introducing LectureBank for NLP Education and Prerequisite Chain Learning*, AAAI 2019. |

Dependencies fetched at build time (not committed here), all compatible with
AGPL-3.0: npm — PGlite and pdf.js (Apache-2.0), Pyodide (MPL-2.0), React,
d3-force-3d, driver.js (MIT), d3 modules (ISC); Clojars — reagent (MIT),
promesa (BSD-2-Clause), ClojureScript with the Google Closure Library
(EPL-1.0 / Apache-2.0), malli (EPL-2.0). The EPL parts are covered by the
section 7 additional permission in [README.md](README.md#licence).

## Lucide — ISC License

Copyright (c) 2026 Lucide Icons and Contributors

Permission to use, copy, modify, and/or distribute this software for any
purpose with or without fee is hereby granted, provided that the above
copyright notice and this permission notice appear in all copies.

THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH
REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY
AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY SPECIAL, DIRECT,
INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM
LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR
OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR
PERFORMANCE OF THIS SOFTWARE.

## Feather — MIT License

Copyright (c) 2013-2023 Cole Bemis

Same MIT permission and warranty text as driver.js below.

## driver.js — MIT License

Copyright (c) 2024 Kamran Ahmed

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
