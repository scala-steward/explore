import "resources/theme/semantic.less";
import "resources/less/style.less";

import App from "sjs/conditions-fastopt.js";

if (module.hot) {
  module.hot.accept();
  App.CondTest.runIOApp();
}
