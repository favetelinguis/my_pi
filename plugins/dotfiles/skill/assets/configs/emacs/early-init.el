;;; early-init.el --- Terminal-only early init -*- lexical-binding: t; -*-

;; Elpaca manages packages, so keep package.el from activating anything.
(setq package-enable-at-startup nil)

;; Defer garbage collection during startup, then restore a sane threshold.
(setq gc-cons-threshold most-positive-fixnum)
(add-hook 'emacs-startup-hook
          (lambda () (setq gc-cons-threshold (* 16 1024 1024))))

;; Log async native-compilation warnings instead of popping up *Warnings*.
(setq native-comp-async-report-warnings-errors 'silent)

;; Terminal only: the menu bar is the only bar a TTY frame can show.
(menu-bar-mode -1)
