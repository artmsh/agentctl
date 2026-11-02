(ns agentctl.adapters.common.schema)

(def mcp-summary
  [:=> [:cat
        [:map
         [:transport {:optional true} :keyword]
         [:url {:optional true} :string]
         [:command {:optional true} :string]
         [:args {:optional true} [:sequential :string]]
         [:cwd {:optional true} :string]
         [:enabled {:optional true} :boolean]]]
   :string])
