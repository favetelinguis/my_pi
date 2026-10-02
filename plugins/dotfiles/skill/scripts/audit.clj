;; Standalone entry point: works from any working directory after git installation.
(require '[babashka.fs :as fs] '[babashka.classpath :as classpath])
(classpath/add-classpath (str (fs/parent (fs/absolutize *file*))))
(require '[dotfiles.audit :as audit])
(apply audit/-main *command-line-args*)
