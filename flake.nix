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
            nodejs_20
            nodePackages.pnpm

            # Database
            postgresql_16

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

            # PostgreSQL setup
            export PGDATA="$PWD/.postgres"
            export PGHOST=localhost
            export PGPORT=5432
            export PGDATABASE=parts_dev
            export PGUSER=$USER

            # Initialize PostgreSQL if needed
            if [ ! -d "$PGDATA" ]; then
              echo "📦 Initializing PostgreSQL database..."
              initdb --auth=trust --no-locale --encoding=UTF8
            fi

            # PostgreSQL management aliases
            alias pg-start='pg_ctl -l $PGDATA/logfile start && sleep 2 && createdb -E UTF8 parts_dev 2>/dev/null || true && createdb -E UTF8 parts_test 2>/dev/null || true && echo "✓ PostgreSQL started (parts_dev, parts_test)"'
            alias pg-stop='pg_ctl stop && echo "✓ PostgreSQL stopped"'
            alias pg-status='pg_ctl status'
            alias pg-console='psql -d parts_dev'
            alias pg-console-test='psql -d parts_test'

            echo "🌀 Parts Development Environment"
            echo ""
            echo "PostgreSQL commands:"
            echo "  pg-start        - Start PostgreSQL and create databases"
            echo "  pg-stop         - Stop PostgreSQL"
            echo "  pg-status       - Check PostgreSQL status"
            echo "  pg-console      - Open psql console (dev DB)"
            echo "  pg-console-test - Open psql console (test DB)"
            echo ""
            echo "Available commands:"
            echo "  make help     - Show all available make targets"
            echo "  make repl     - Start development REPL"
            echo "  make test     - Run tests"
            echo "  nix flake update - Update all dependencies"
            echo ""
            echo "Java:       $(java -version 2>&1 | head -n 1)"
            echo "Clojure:    $(clojure --version)"
            echo "Node:       $(node --version)"
            echo "pnpm:       $(pnpm --version)"
            echo "PostgreSQL: $(postgres --version)"
          '';
        };
      });
}
