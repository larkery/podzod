{
  description = "podzod: personal gpodder sync server + Lyrion 'play something sensible next'";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    clj-nix = {
      url = "github:jlesquembre/clj-nix";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs = { self, nixpkgs, clj-nix }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" ];
      forAll = f: nixpkgs.lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});
    in
    {
      # Needs deps-lock.json: nix run github:jlesquembre/clj-nix#deps-lock
      packages = forAll (pkgs: {
        default = clj-nix.lib.mkCljApp {
          inherit pkgs;
          modules = [{
            projectSrc = ./.;
            name = "podzod/podzod";
            version = "1.0.0";
            main-ns = "podzod.main";
            jdk = pkgs.jdk21_headless;
          }];
        };
      });

      nixosModules.default = import ./nix/module.nix self;
    };
}
