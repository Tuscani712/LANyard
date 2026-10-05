// Applied before the stylesheet so the theme does not flash. The product look
// is dark; only an explicit "light" choice switches away from it.
(function () {
  try {
    var t = localStorage.getItem("lanyard.theme");
    document.documentElement.dataset.theme = (t === "light") ? "light" : "dark";
  } catch (e) {
    document.documentElement.dataset.theme = "dark";
  }
})();
