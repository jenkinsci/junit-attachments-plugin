document.addEventListener("DOMContentLoaded", () => {
  document.querySelectorAll("a.gallery").forEach((el) => {
    el.addEventListener("click", (evt) => {
      const img = new Image();
      img.src = el.href;
      img.alt = el.textContent.trim();
      dialog.modal(img);
      evt.preventDefault();
    });
  });
});
