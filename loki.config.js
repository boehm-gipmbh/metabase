module.exports = {
  diffingEngine: "looks-same",
  storiesFilter: [
    "static-viz",
    "viz",
    "^visualizations/shared",
    "^embed",
    "^design system",
    "^Inputs/DatePicker Dates range",
    "^Buttons/Button Compact size, custom color",
  ].join("|"),
  configurations: {
    "chrome.laptop": {
      target: "chrome.docker",
      width: 1366,
      height: 768,
      deviceScaleFactor: 1,
      mobile: false,
    },
  },
  "looks-same": {
    strict: false,
    tolerance: 6,
    antialiasingTolerance: 0,
    ignoreAntialiasing: true,
    ignoreCaret: true,
  },
};
