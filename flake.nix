{
  description = "Parts project development environment";

  inputs = {
    # Use nixpkgs-unstable for latest Clojure 1.12.1
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = nixpkgs.legacyPackages.${system};
        
        # Java 21 Temurin
        jdk = pkgs.temurin-bin-21;
      in {
        devShells.default = pkgs.mkShell {
          buildInputs = with pkgs; [
            # Java
            jdk
            
            # Clojure
            clojure
            clj-kondo
            
            # JavaScript tools
            bun
            nodejs_20
            
            # Database
            sqlite
            
            # Build tools
            gnumake
            
            # Development tools
            git
            ripgrep
            fd
            
            # Optional but useful
            jq
            httpie
            watchexec
          ];
          
          shellHook = ''
            export JAVA_HOME=${jdk}
            
            # Set up local paths for npm packages
            export PATH="$PWD/node_modules/.bin:$PATH"
            
            echo "🌀 Parts Development Environment"
            echo ""
            echo "Available commands:"
            echo "  make help     - Show all available make targets"
            echo "  make repl     - Start development REPL"
            echo "  make test     - Run tests"
            echo "  nix flake update - Update all dependencies"
            echo ""
            echo "Java:     $(java -version 2>&1 | head -n 1)"
            echo "Clojure:  $(clojure --version)"
            echo "Bun:      $(bun --version)"
            echo "Node:     $(node --version)"
          '';
        };
      });
}
