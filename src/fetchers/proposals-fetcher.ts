/*
 * Copyright (C) 2018 The 'MysteriumNetwork/mysterion' Authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

import { action } from 'mobx'
import { ProposalDTO, ProposalsFilter } from 'mysterium-tequilapi'
import {
  Proposal,
  sortFavorites
} from '../libraries/favorite-proposal'
import { store } from '../store/app-store'
import { FetcherBase } from './fetcher-base'

type ProposalsFetcherProps = {
  findProposals (filter?: ProposalsFilter): Promise<ProposalDTO[]>
}

export class ProposalsFetcher extends FetcherBase<Proposal[]> {
  constructor (private props: ProposalsFetcherProps) {
    super('Proposals')
  }

  protected async fetch (): Promise<Proposal[]> {
    const proposals: ProposalDTO[] = await this.props.findProposals()
    return sortFavorites(proposals)
  }

  @action
  protected update (proposals: Proposal[]) {
    store.Proposals = proposals

    // TODO: support non-selected proposal
    // ensure that proposal is always selected
    const containsSelectedProvider = store.Proposals.some(
      (p: Proposal) => p.id === store.SelectedProviderId
    )
    if (!containsSelectedProvider) {
      store.SelectedProviderId = store.Proposals[0].id
    }
  }
}