// Applied before the stylesheet so a saved theme does not flash the default.
// "system" leaves data-theme unset and the prefers-color-scheme rule applies.
(function () {
  try {
    var t = localStorage.getItem("lanyard.theme");
    if (t === "light" || t === "dark") document.documentElement.dataset.theme = t;
  } catch (e) { /* storage may be disabled */ }
})();
