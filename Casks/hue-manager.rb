cask "hue-manager" do
  version "2026.06.23-3530e25"
  sha256 "6eca40d3a3d80f859d173db3057c35953e62f9493a6da773d7935a702e683c93"

  url "https://api.github.com/repos/CommanderTvis/hue-manager/releases/assets/455775334",
      header: [
        "Authorization: token #{ENV.fetch("HOMEBREW_GITHUB_API_TOKEN", "")}",
        "Accept: application/octet-stream",
      ]
  name "Hue Manager"
  desc "Philips Hue lamp management desktop application"
  homepage "https://github.com/CommanderTvis/hue-manager"

  depends_on macos: ">= :ventura"

  app "Hue Manager.app"

  zap trash: [
    "~/Library/Preferences/io.github.commandertvis.huemanager.plist",
    "~/Library/Application Support/io.github.commandertvis.huemanager",
  ]
end
